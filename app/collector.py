"""触控板采集子进程：以管道替代原来的 UDP/OSC 端口。

进程生命周期由主程序完全掌控：应用启动即拉起，关窗即退出（``atexit`` 兜底），
因此不再需要「先开采集器、再开服务」的启动顺序，也不存在端口占用问题。

管道协议（行分隔 JSON，UTF-8，两侧均按行 flush）：

采集器 → 应用
    {"type": "hello",  "pid": 1234, "protocol": 1, "version": "1.0"}
    {"type": "status", "devices": 1, "summary": "...", "paused": false, "diagnostics": "..."}
    {"type": "gesture", "address": "/down", "args": [0, 0.42, 0.61, 0.004, 3]}
    {"type": "log",    "level": "info", "message": "..."}
    {"type": "error",  "message": "...", "fatal": false}
    {"type": "pong",   "id": 7}

应用 → 采集器
    {"cmd": "REC_START"} | {"cmd": "REC_STOP"} | {"cmd": "PAUSE"} | {"cmd": "RESUME"}
    {"cmd": "PING", "id": 7} | {"cmd": "QUIT"}
"""

from __future__ import annotations

import atexit
import json
import logging
import os
import subprocess
import threading
import time

log = logging.getLogger(__name__)

#: 单条管道路由消息的最大长度，超出视为协议错乱（防止内存被垃圾数据撑爆）
MAX_LINE_BYTES = 1 << 20

#: 采集器启动后就绪等待时间
START_TIMEOUT = 3.0


class CollectorProcess:
    def __init__(self, core, exe_path: str) -> None:
        self.core = core
        self.exe_path = exe_path
        self._process: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self._alive = False
        self._want_running = False
        self._pong_events: dict[int, threading.Event] = {}
        self._ping_seq = 0
        self._threads: list[threading.Thread] = []

    # ------------------------------------------------------------------
    # 生命周期
    # ------------------------------------------------------------------
    @property
    def alive(self) -> bool:
        return self._alive and self._process is not None and self._process.poll() is None

    def start(self) -> bool:
        """拉起采集器；失败时把原因写进界面状态而不是抛异常。"""
        if self.alive:
            return True
        if not os.path.isfile(self.exe_path):
            message = f"未找到采集器程序：{self.exe_path}"
            log.error(message)
            self.core.update_collector(running=False, devices=0, summary="采集器缺失", error=message)
            self.core.toast("未找到采集器 TouchBridge.exe，请运行 build_exe.bat 重新构建", "error")
            return False

        creationflags = 0
        startupinfo = None
        if os.name == "nt":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW

        try:
            process = subprocess.Popen(
                [self.exe_path, "--pipe"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=os.path.dirname(self.exe_path) or None,
                encoding="utf-8",
                errors="replace",
                bufsize=1,           # 行缓冲
                text=True,
                creationflags=creationflags,
                startupinfo=startupinfo,
            )
        except OSError as exc:
            message = f"采集器启动失败：{exc}"
            log.exception(message)
            self.core.update_collector(running=False, devices=0, summary="采集器启动失败", error=message)
            self.core.toast(message, "error")
            return False

        self._process = process
        self._alive = True
        self._want_running = True
        self.core.update_collector(running=True, summary="采集器已连接，正在等待触控板…", error=None)

        self._threads = [
            threading.Thread(target=self._read_stdout, args=(process,), name="collector-out", daemon=True),
            threading.Thread(target=self._read_stderr, args=(process,), name="collector-err", daemon=True),
        ]
        for thread in self._threads:
            thread.start()
        atexit.register(self.stop)
        log.info("采集器已启动 pid=%s exe=%s", process.pid, self.exe_path)
        return True

    def restart(self) -> bool:
        self.stop()
        time.sleep(0.2)
        return self.start()

    def stop(self) -> None:
        process = self._process
        if process is None:
            self._alive = False
            self._want_running = False
            return

        self._want_running = False
        forced = False
        try:
            self._write({"cmd": "QUIT"})
            try:
                process.stdin.close()
            except Exception:
                pass
            process.wait(timeout=2.0)
        except Exception:
            forced = True
            try:
                process.terminate()
                process.wait(timeout=2.0)
            except Exception:
                try:
                    process.kill()
                    process.wait(timeout=2.0)
                except Exception:
                    log.exception("强制结束采集器失败")
        finally:
            if forced or process.returncode not in (None, 0):
                try:
                    subprocess.run([self.exe_path, "--restore"], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                                   timeout=5, check=True)
                except (OSError, subprocess.SubprocessError):
                    log.exception("恢复系统设置失败，请重新启动应用以重试恢复")
            for thread in self._threads:
                if thread is not threading.current_thread():
                    thread.join(timeout=1.0)
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream is not None:
                    try:
                        stream.close()
                    except OSError:
                        pass
            self._threads.clear()
            self._process = None
            self._alive = False
            self.core.update_collector(running=False, devices=0, locked=False, summary="采集器已停止")
            log.info("采集器已退出")

    # ------------------------------------------------------------------
    # 发送命令
    # ------------------------------------------------------------------
    def send(self, command: str, **extra) -> bool:
        payload = {"cmd": command}
        payload.update(extra)
        return self._write(payload)

    def _write(self, payload: dict) -> bool:
        process = self._process
        if process is None or process.stdin is None or process.poll() is not None:
            return False
        data = json.dumps(payload, ensure_ascii=False)
        with self._lock:
            try:
                process.stdin.write(data + "\n")
                process.stdin.flush()
                return True
            except (OSError, ValueError) as exc:
                log.warning("写管道失败：%s", exc)
                return False

    def ping(self, timeout: float = 2.0) -> float | None:
        """往返时延探测，用于「采集链路自检」。"""
        if not self.alive:
            return None
        with self._lock:
            self._ping_seq += 1
            token = self._ping_seq
            event = threading.Event()
            self._pong_events[token] = event
        started = time.monotonic()
        if not self.send("PING", id=token):
            self._pong_events.pop(token, None)
            return None
        ok = event.wait(timeout)
        self._pong_events.pop(token, None)
        return (time.monotonic() - started) if ok else None

    # ------------------------------------------------------------------
    # 读取线程
    # ------------------------------------------------------------------
    def _read_stdout(self, process) -> None:
        if process is None or process.stdout is None:
            return
        for raw in process.stdout:
            line = raw.strip()
            if not line:
                continue
            if len(line) > MAX_LINE_BYTES:
                log.warning("丢弃超长管道消息（%s 字节）", len(line))
                continue
            try:
                event = json.loads(line)
            except ValueError:
                log.debug("忽略非 JSON 管道输出：%s", line[:200])
                continue
            try:
                self._dispatch(event)
            except Exception:
                log.exception("处理管道消息失败：%s", line[:200])

        # 管道 EOF：子进程已退出
        if self._process is not process:
            return
        self._alive = False
        code = None
        if process is not None:
            try:
                code = process.wait(timeout=1.0)
            except Exception:
                pass
        if self._want_running:
            message = f"采集器意外退出（退出码 {code}），请点「重新连接采集器」"
            log.error(message)
            self.core.update_collector(running=False, devices=0, summary="采集器已退出", error=message)
            self.core.toast(message, "error")

    def _read_stderr(self, process) -> None:
        if process is None or process.stderr is None:
            return
        for raw in process.stderr:
            text = raw.rstrip()
            if text:
                log.warning("[collector] %s", text)

    def _dispatch(self, event: dict) -> None:
        kind = event.get("type")

        if kind == "gesture":
            address = event.get("address")
            args = event.get("args") or []
            if isinstance(address, str):
                self.core.handle_gesture(address, args)
            return

        if kind == "frame":
            self.core.bus.publish("frame", event)
            return

        if kind == "status":
            self.core.update_collector(
                running=True,
                devices=int(event.get("devices") or 0),
                summary=event.get("summary") or "",
                paused=bool(event.get("paused", False)),
                diagnostics=event.get("diagnostics") or "",
                locked=bool(event.get("locked", False)),
                warning=event.get("warning"),
                error=event.get("error"),
            )
            return

        if kind == "pong":
            token = event.get("id")
            waiter = self._pong_events.get(int(token)) if token is not None else None
            if waiter is not None:
                waiter.set()
            return

        if kind == "hello":
            log.info("采集器就绪 pid=%s version=%s", event.get("pid"), event.get("version"))
            return

        if kind == "log":
            log.info("[collector] %s", event.get("message"))
            return

        if kind == "error":
            message = str(event.get("message") or "采集器报错")
            log.error("[collector] %s", message)
            if event.get("fatal"):
                self.core.update_collector(running=False, summary="采集器报错", error=message)
                self.core.toast(f"采集器错误：{message}", "error")
            else:
                self.core.toast(f"采集器提示：{message}", "warn")
            return

        log.debug("未知管道事件：%s", event)