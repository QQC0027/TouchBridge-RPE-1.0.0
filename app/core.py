"""应用核心：全局状态与全部业务方法。

原 ``backend/app.py`` 里的 SocketIO 事件处理器被原样搬成方法，算法与判定逻辑
保持不变；唯一区别是「发事件」从 ``socketio.emit`` 变为 ``self.bus.publish``。
界面通过 ``app.ui_bridge.UiBridge.call`` 调用这些方法，全程在同一个进程内完成。
"""

from __future__ import annotations

import base64
import json
import math
import mimetypes
import os
import pathlib
import random
import shutil
import subprocess
import threading
import time

from . import defaults
from .engine import BeatClock, GestureTracker, build_rpe, convert_notes, simulate_session
from .pez import COVER_EXT_MAP, PezPacker, is_image

#: 单张曲绘上限（超过视为异常文件，避免把巨大图片塞进谱包）
MAX_COVER_BYTES = 10 * 1024 * 1024

#: 音频文件允许的扩展名（仅用于原生对话框过滤与提示）
AUDIO_EXTENSIONS = (".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".opus", ".mp4")


def sanitize_filename(raw: str, fallback: str) -> str:
    """去掉路径分隔符与控制字符，但保留中文（不再依赖 werkzeug.secure_filename）。"""
    name = os.path.basename((raw or "").replace("\\", "/")).strip()
    cleaned = "".join(ch for ch in name if ch >= " " and ch not in '<>:"/|?*')
    cleaned = cleaned.strip(" .")
    return cleaned or fallback


def _uri(path: str) -> str | None:
    try:
        return pathlib.Path(path).as_uri()
    except (ValueError, OSError):
        return None


class AppCore:
    """所有业务状态的唯一持有者。"""

    def __init__(self, paths, bus) -> None:
        self.lock = threading.RLock()
        self.paths = paths
        self.bus = bus
        self.config = self._load_config()
        self.clock = self._build_clock()
        self.notes: list[dict] = []          # 收集到的「完成的音符」
        self.autochart_undo = None
        self.recording = False
        self.last_audio_time = 0.0           # 界面播放器上报的时间(秒)
        self.last_audio_ts: float | None = None
        self.audio_playing = False
        self.manual_start = time.monotonic()
        self.tracker: GestureTracker | None = None
        self.self_test_ok = False
        self.collector = None                # app.collector.CollectorProcess
        self.collector_status: dict = {
            "running": False,
            "devices": 0,
            "summary": "正在连接触控板采集器…",
            "diagnostics": "",
            "paused": False,
            "error": None,
        }

        # 重启后从 config 恢复音频/曲绘（否则直接导出的 pez 会缺音乐）
        meta = self.config["meta"]
        song = meta.get("song")
        self.audio_filename = song if song and os.path.isfile(paths.upload_path(song)) else None
        cover = meta.get("cover")
        self.cover_filename = cover if cover and os.path.isfile(paths.upload_path(cover)) else None

        self.packer = PezPacker(paths.upload_dir, paths.export_dir, self)
        self.autochart = None

    # ------------------------------------------------------------------
    # 配置
    # ------------------------------------------------------------------
    def _load_config(self) -> dict:
        try:
            with open(self.paths.config_path, "r", encoding="utf-8") as handle:
                return defaults.normalise(json.load(handle))
        except (OSError, ValueError):
            return defaults.new_config()

    def save_config(self) -> None:
        try:
            with open(self.paths.config_path, "w", encoding="utf-8") as handle:
                json.dump(self.config, handle, ensure_ascii=False, indent=2)
        except OSError as exc:
            self.toast(f"配置保存失败：{exc}", "error")

    def _build_clock(self) -> BeatClock:
        settings = self.config["settings"]
        return BeatClock(
            bpm=settings.get("bpm", 120),
            offset_ms=settings.get("offset_ms", 0),
            beats_per_bar=settings.get("beats_per_bar", 4),
            segments=settings.get("segments") or None,
        )

    def rebuild_clock(self) -> None:
        self.clock = self._build_clock()

    # ------------------------------------------------------------------
    # 时间与状态
    # ------------------------------------------------------------------
    def current_music_time(self) -> float:
        """全局音乐时间(秒)。优先用界面播放器上报的音频时钟，否则回退单调时钟。"""
        if self.audio_filename and self.last_audio_ts is not None:
            if self.audio_playing:
                elapsed = time.monotonic() - self.last_audio_ts
                return self.last_audio_time + elapsed
            return self.last_audio_time
        return time.monotonic() - self.manual_start

    def public_state(self) -> dict:
        settings = self.config["settings"]
        return {
            "settings": settings,
            "meta": self.config["meta"],
            "recording": self.recording,
            "audio_loaded": bool(self.audio_filename),
            "audio_filename": self.audio_filename,
            "audio_uri": _uri(self.paths.upload_path(self.audio_filename)) if self.audio_filename else None,
            "cover_loaded": bool(self.cover_filename),
            "cover_filename": self.cover_filename,
            "cover_uri": _uri(self.paths.upload_path(self.cover_filename)) if self.cover_filename else None,
            "audio_time": round(self.current_music_time(), 3),
            "note_count": len(self.notes),
            "collector": dict(self.collector_status),
            "self_test_ok": self.self_test_ok,
            "autochart_enabled": self.config.get("autochart_enabled", True),
            "autochart_can_undo": self.autochart_undo is not None,
            "data_dir": self.paths.data_dir,
            "export_dir": self.paths.export_dir,
        }

    def emit_state(self) -> None:
        self.bus.publish("state", self.public_state())

    def toast(self, message: str, level: str = "info") -> None:
        self.bus.publish("toast", {"msg": message, "level": level})

    # ------------------------------------------------------------------
    # 采集器
    # ------------------------------------------------------------------
    def set_collector(self, collector) -> None:
        self.collector = collector

    def update_collector(self, **kwargs) -> None:
        changed = False
        for key, value in kwargs.items():
            if self.collector_status.get(key) != value:
                self.collector_status[key] = value
                changed = True
        if changed:
            self.emit_state()

    def _send_collector(self, command: str, **extra) -> bool:
        collector = self.collector
        if collector is None:
            return False
        try:
            return bool(collector.send(command, **extra))
        except Exception:
            return False

    def collector_reconnect(self) -> dict:
        collector = self.collector
        if collector is None:
            return {"ok": False, "error": "采集器未初始化"}
        self.update_collector(running=False, summary="正在重启采集器…", error=None)
        ok = collector.restart()
        self.toast("采集器已重新连接" if ok else "采集器重启失败，详情见日志", "success" if ok else "error")
        self.emit_state()
        return {"ok": ok, "error": None if ok else "采集器重启失败"}

    def collector_selftest(self) -> dict:
        """等价于原 OSC 自检：验证「采集器 → 管道 → 应用」整条链路是否通。"""
        collector = self.collector
        if collector is None or not getattr(collector, "alive", False):
            self.self_test_ok = False
            self.toast("采集器未运行，请先点「重新连接采集器」", "error")
            self.emit_state()
            return {"ok": False, "error": "采集器未运行"}

        rtt = collector.ping(timeout=2.0)
        if rtt is None:
            self.self_test_ok = False
            self.toast("采集链路自检失败：管道无响应，请查看日志", "error")
            self.emit_state()
            return {"ok": False, "error": "管道无响应"}

        self.self_test_ok = True
        devices = int(self.collector_status.get("devices") or 0)
        if devices > 0:
            self.toast(f"采集链路自检通过：往返 {rtt * 1000:.1f} ms，{devices} 台触控板在线", "success")
        else:
            self.toast("管道联通正常，但未发现精确式触控板（请检查触控板驱动）", "warn")
        self.emit_state()
        return {"ok": True, "rtt_ms": round(rtt * 1000.0, 1), "devices": devices}

    # ------------------------------------------------------------------
    # 手势输入（由采集管道调用）
    # ------------------------------------------------------------------
    def handle_gesture(self, address: str, args: list) -> None:
        with self.lock:
            self._handle_gesture(address, args)

    def _handle_gesture(self, address: str, args: list) -> None:
        self.bus.publish("gesture_raw", {"address": address, "args": list(args)})
        if not self.recording or self.tracker is None:
            return

        tracker = self.tracker
        a = args
        try:
            if address == "/down" and len(a) >= 5:
                tracker.on_down(int(a[0]), float(a[1]), float(a[2]), float(a[3]), int(a[4]))
            elif address == "/move" and len(a) >= 4:
                tracker.on_move(int(a[0]), float(a[1]), float(a[2]), float(a[3]))
            elif address == "/hold" and len(a) >= 3:
                tracker.on_hold(int(a[0]), float(a[1]), float(a[2]))
            elif address == "/drag" and len(a) >= 3:
                tracker.on_drag(int(a[0]), float(a[1]), float(a[2]))
            elif address == "/tap" and len(a) >= 4:
                tracker.on_tap(int(a[0]), float(a[1]), float(a[2]), float(a[3]))
            elif address == "/up" and len(a) >= 3:
                tracker.on_up(int(a[0]), float(a[1]), float(a[2]))
            # /count 仅用于监视，不需要还原
        except Exception as exc:
            self.toast(f"手势解析异常: {exc}", "error")
            return

        if tracker.completed:
            self.notes.extend(tracker.completed)
            tracker.completed = []
            self.emit_state()

    # ------------------------------------------------------------------
    # 音频与曲绘
    # ------------------------------------------------------------------
    def audio_time(self, data: dict) -> dict:
        try:
            self.last_audio_time = float(data.get("t", 0))
            self.audio_playing = bool(data.get("playing", False))
            self.last_audio_ts = time.monotonic()
        except (TypeError, ValueError):
            pass
        return {"ok": True}

    def audio_ready(self, filename: str | None) -> dict:
        """界面播放器就绪回调（导入音频后由前端触发），用于校准时钟起点。"""
        if filename:
            if self.audio_filename != filename:
                self.autochart_undo = None
            self.audio_filename = filename
            self.config["meta"]["song"] = filename
            self.save_config()
        self.last_audio_time = 0.0
        self.last_audio_ts = None
        self.audio_playing = False
        self.emit_state()
        self.toast(f"音频已就绪：{filename}", "success")
        return {"ok": True, "filename": filename}

    def set_audio_file(self, path: str) -> dict:
        """把一个本地音频文件复制进 data/uploads 并设为当前曲目。"""
        source = os.path.abspath(path)
        if not os.path.isfile(source):
            return {"ok": False, "error": "文件不存在"}
        filename = sanitize_filename(source, "audio.bin")
        destination = self.paths.upload_path(filename)
        try:
            if os.path.abspath(destination) != source:
                shutil.copy2(source, destination)
        except OSError as exc:
            return {"ok": False, "error": f"复制音频失败：{exc}"}

        if self.audio_filename != filename:
            self.autochart_undo = None
        self.audio_filename = filename
        self.config["meta"]["song"] = filename
        self.save_config()
        self.last_audio_time = 0.0
        self.last_audio_ts = None
        self.audio_playing = False
        self.emit_state()
        self.toast(f"已导入音频：{filename}", "success")
        return {
            "ok": True,
            "filename": filename,
            "uri": _uri(destination),
            "state": self.public_state(),
        }

    def set_cover_file(self, path: str) -> dict:
        source = os.path.abspath(path)
        if not os.path.isfile(source):
            return {"ok": False, "error": "文件不存在"}
        ext = os.path.splitext(source)[1].lower()
        if ext not in COVER_EXT_MAP:
            return {"ok": False, "error": "请选择图片文件（png/jpg/gif/webp/bmp）"}
        try:
            if os.path.getsize(source) > MAX_COVER_BYTES:
                return {"ok": False, "error": "图片过大（上限 10MB）"}
            with open(source, "rb") as handle:
                data = handle.read()
        except OSError as exc:
            return {"ok": False, "error": f"读取图片失败：{exc}"}
        if not is_image(data):
            return {"ok": False, "error": "文件内容不是有效的图片"}

        # 用稳定的 ASCII 文件名，避免中文/空格进入谱包
        safe = "cover_" + str(int(time.time())) + ext
        try:
            with open(self.paths.upload_path(safe), "wb") as handle:
                handle.write(data)
        except OSError as exc:
            return {"ok": False, "error": f"保存图片失败：{exc}"}

        self.cover_filename = safe
        self.config["meta"]["cover"] = safe
        self.save_config()
        self.emit_state()
        self.toast("曲绘已更新", "success")
        return {"ok": True, "filename": safe, "uri": _uri(self.paths.upload_path(safe)),
                "state": self.public_state()}

    def cover_clear(self) -> dict:
        self.cover_filename = None
        if "cover" in self.config.get("meta", {}):
            self.config["meta"]["cover"] = None
            self.save_config()
        self.emit_state()
        self.toast("已清除曲绘，导出将用渐变占位图", "info")
        return {"ok": True}

    def audio_payload(self) -> dict:
        """音频二进制兜底通道：``file://`` 无法播放时转 base64 交给界面的 Blob。"""
        return self._payload_of(self.audio_filename, "audio/mpeg")

    def cover_payload(self) -> dict:
        return self._payload_of(self.cover_filename, "image/png")

    def _payload_of(self, filename: str | None, fallback_mime: str) -> dict:
        if not filename:
            return {"ok": False, "error": "尚未导入文件"}
        path = self.paths.upload_path(filename)
        try:
            size = os.path.getsize(path)
            if size > 64 * 1024 * 1024:
                return {"ok": False, "error": "文件超过 64MB，请改用较小的音频"}
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError as exc:
            return {"ok": False, "error": f"读取失败：{exc}"}
        mime = mimetypes.guess_type(filename)[0] or fallback_mime
        return {"ok": True, "mime": mime, "data": base64.b64encode(data).decode("ascii")}

    # ------------------------------------------------------------------
    # 设置 / 录制 / 预览
    # ------------------------------------------------------------------
    def save_settings(self, data: dict) -> dict:
        if isinstance(data.get("settings"), dict):
            for key, value in data["settings"].items():
                self.config["settings"][key] = value
        if isinstance(data.get("meta"), dict):
            self.config["meta"].update(data["meta"])
        self.save_config()
        self.rebuild_clock()
        self.emit_state()
        self.toast("设置已保存", "success")
        return {"ok": True, "state": self.public_state()}

    def monitor_stop(self) -> dict:
        if self.recording:
            self.record_stop()
        if not self._send_collector("PAUSE"):
            return {"ok": False, "error": "采集器未连接"}
        self.update_collector(paused=True)
        return {"ok": True, "state": self.public_state()}

    def monitor_start(self) -> dict:
        if not self._send_collector("RESUME"):
            return {"ok": False, "error": "采集器未连接"}
        self.update_collector(paused=False)
        return {"ok": True, "state": self.public_state()}

    def record_start(self) -> dict:
        if self.recording:
            return {"ok": True, "state": self.public_state()}
        if self.collector is not None:
            self._send_collector("RESUME")
            self._send_collector("CLEAR")
        self.autochart_undo = None
        self.recording = True
        self.tracker = GestureTracker(self.current_music_time, self.config["settings"])
        if not self._send_collector("REC_START"):
            self.toast("采集器未响应，专注模式未启用（采集仍在继续）", "warn")
        self.emit_state()
        self.toast("录制中：跟着音乐打板；ESC 解除鼠标锁定，Alt+Enter 重新锁定；点「停止录制」结束", "success")
        return {"ok": True, "state": self.public_state()}

    def record_stop(self) -> dict:
        self.recording = False
        if self.tracker is not None:
            self.tracker.flush_open()
            if self.tracker.completed:
                self.notes.extend(self.tracker.completed)
                self.tracker.completed = []
        if not self._send_collector("REC_STOP"):
            self.toast("采集器未响应，请检查采集器状态", "warn")
        self.emit_state()
        self.toast(f"录制停止，共 {len(self.notes)} 个音符", "info")
        return {"ok": True, "state": self.public_state()}

    def clear_events(self) -> dict:
        self.autochart_undo = None
        self.notes = []
        self._send_collector("CLEAR")
        self.tracker = GestureTracker(self.current_music_time, self.config["settings"])
        self.emit_state()
        self.toast("已清空音符", "info")
        return {"ok": True, "state": self.public_state()}

    def sim_inject(self) -> dict:
        settings = self.config["settings"]
        notes, _ = simulate_session(duration_sec=4.0, bpm=settings["bpm"],
                                   offset_ms=settings["offset_ms"],
                                   seed=random.randint(1, 9999))
        self.notes.extend(notes)
        self.emit_state()
        self.toast(f"已注入 {len(notes)} 个模拟音符（含 Tap/Hold/Flick/Drag）", "success")
        return {"ok": True, "count": len(notes), "state": self.public_state()}

    def auto_calibrate(self) -> dict:
        """根据已录制音符相对最近整拍的偏差，自动估算采样补偿偏移(ms)并保存。

        原理：用户跟拍敲击，命中时刻本应落在整拍上；若录音链路 / 音频输出延迟
        导致系统性偏早(快)或偏晚(慢)，所有音符会相对整拍有一个恒定偏差 b(以拍计)。
        取每个音符命中拍的小数部分(循环到 [-0.5,0.5))，其中位数即偏差 b，
        则补偿偏移 = -b * 每拍秒数。正值会让音符延后，抵消「偏早」。
        （假设用户敲在整拍上；若主要敲在反拍/半拍，请改用手动补偿。）
        """
        if not self.notes:
            self.toast("还没有录制到音符，先录一段再点自动校准", "info")
            return {"ok": False, "error": "还没有录制到音符"}
        try:
            bpm = float(self.clock.segments[0]["bpm"])
            beat_dur = 60.0 / bpm if bpm > 0 else 0.5
            fracs: list[float] = []
            for note in self.notes:
                t = note.get("t_sec")
                if t is None:
                    continue
                beat = self.clock.time_to_beat(t)
                frac = beat - math.floor(beat)
                if frac > 0.5:
                    frac -= 1.0
                fracs.append(frac)
            if not fracs:
                self.toast("没有可用的音符时间戳", "info")
                return {"ok": False, "error": "没有可用的音符时间戳"}

            fracs.sort()
            bias = fracs[len(fracs) // 2]
            offset_ms = round(-bias * beat_dur * 1000.0, 1)
            self.config["settings"]["hit_offset_ms"] = offset_ms
            self.save_config()
            self.bus.publish("calibrated", {"hit_offset_ms": offset_ms})
            self.toast(f"已自动校准：采样补偿 = {offset_ms:+.1f} ms（正数=音符延后，用于抵消偏早）", "success")
            self.emit_state()
            return {"ok": True, "hit_offset_ms": offset_ms}
        except Exception as exc:
            self.toast(f"校准失败: {exc}", "error")
            return {"ok": False, "error": str(exc)}

    def get_preview(self) -> dict:
        notes = convert_notes(self.notes, self.clock, self.config["settings"])
        music_time = self.current_music_time()
        playhead = self.clock.time_to_beat(music_time)
        settings = self.config["settings"]
        return {
            "ok": True,
            "preview": {
                "notes": notes,
                "playhead": round(playhead, 4),
                "audioTime": round(music_time, 4),
                "bpm": settings["bpm"],
                "beats_per_bar": settings["beats_per_bar"],
                "segments": self.clock.segments,
                "offset_ms": self.clock.offset_sec * 1000.0,
            },
        }

    # ------------------------------------------------------------------
    # 导出
    # ------------------------------------------------------------------
    def export_rpe(self) -> dict:
        rpe, stats = build_rpe(self.notes, self.clock, self.config["settings"], self.config["meta"])
        name = (self.config["meta"].get("name") or "chart").strip().replace(" ", "_") or "chart"
        pez_fname, pez_note = self.packer.build(rpe, name)

        if pez_fname:
            message = f"已导出通用谱包 {pez_fname}：{stats}"
            if pez_note:
                message += f"（{pez_note}）"
            path = self.paths.export_path(pez_fname)
            self.bus.publish("exported", {"pez": pez_fname, "stats": stats, "path": path})
            self.toast(message, "success")
            return {"ok": True, "pez": pez_fname, "path": path, "stats": stats, "note": pez_note}

        self.bus.publish("exported", {"pez": None, "stats": stats, "path": None})
        self.toast(f"导出失败：{stats}", "error")
        return {"ok": False, "pez": None, "stats": stats, "error": str(stats)}

    def export_path(self, filename: str) -> str | None:
        path = self.paths.export_path(os.path.basename(filename or ""))
        return path if os.path.isfile(path) else None

    def open_exports(self) -> dict:
        """在文件管理器中打开导出目录（解决「文件放哪了」的问题）。"""
        try:
            os.makedirs(self.paths.export_dir, exist_ok=True)
            subprocess.Popen(["explorer", self.paths.export_dir])
            return {"ok": True, "dir": self.paths.export_dir}
        except Exception as exc:
            self.toast(f"打开导出目录失败：{exc}", "error")
            return {"ok": False, "error": str(exc)}

    # ------------------------------------------------------------------
    def shutdown(self) -> None:
        self.recording = False
        self.tracker = None