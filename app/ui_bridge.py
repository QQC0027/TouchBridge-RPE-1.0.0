"""界面桥：WebView 与 Python 核心之间的唯一通道。

替代原来的 socket.io + HTTP：

* **JS → Python**：``window.pywebview.api.call(event, payload)``，返回 Promise。
* **Python → JS**：界面每 50ms 调用一次 ``poll_events``，取走事件总线里排队的事件。
  这样任何工作线程都不必触碰 WebView，规避了 WebView2 的线程限制。
* **文件**：导入/导出改用 Windows 原生对话框，不再有 ``/upload``、``/download``。
"""

from __future__ import annotations

import base64
import json
import logging
import os
import shutil

import webview

from .core import AUDIO_EXTENSIONS
from .pez import COVER_EXT_MAP

log = logging.getLogger(__name__)

AUDIO_FILTER = ("音频文件 (*.mp3;*.wav;*.ogg;*.flac;*.m4a;*.aac;*.opus)", "所有文件 (*.*)")
IMAGE_FILTER = ("图片 (*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp)", "所有文件 (*.*)")
JSON_FILTER = ("自动制谱 points (*.json)", "所有文件 (*.*)")
PEZ_FILTER = ("RPE 谱包 (*.pez)", "所有文件 (*.*)")
ZIP_FILTER = ("压缩包 (*.zip)", "所有文件 (*.*)")


def _dialog_kind(name: str):
    """兼容 pywebview 4.x（``OPEN_DIALOG``）与 5.x（``FileDialog.OPEN``）。"""
    file_dialog = getattr(webview, "FileDialog", None)
    if file_dialog is not None:
        try:
            return getattr(file_dialog, name)
        except AttributeError:
            pass
    return getattr(webview, f"{name}_DIALOG")


class UiBridge:
    def __init__(self, core, bus) -> None:
        self._core = core
        self._bus = bus
        self._window = None
        self._handlers = {
            # 状态与设置
            "get_state": lambda _p: {"ok": True, "state": self._core.public_state()},
            "save_settings": lambda p: self._core.save_settings(p),
            "poll_events": lambda _p: {"ok": True, "events": self._bus.drain()},
            # 音频
            "audio_time": lambda p: self._core.audio_time(p),
            "audio_ready": lambda p: self._core.audio_ready(p.get("filename")),
            "audio_blob": lambda _p: self._core.audio_payload(),
            "pick_audio": lambda _p: self._pick_audio(),
            # 曲绘
            "pick_cover": lambda _p: self._pick_cover(),
            "cover_clear": lambda _p: self._core.cover_clear(),
            "cover_blob": lambda _p: self._core.cover_payload(),
            # 采集
            "collector_pause": lambda _p: self._core.monitor_stop(),
            "collector_resume": lambda _p: self._core.monitor_start(),
            "collector_selftest": lambda _p: self._core.collector_selftest(),
            "collector_reconnect": lambda _p: self._core.collector_reconnect(),
            # 录制与导出
            "record_start": lambda _p: self._core.record_start(),
            "record_stop": lambda _p: self._core.record_stop(),
            "clear_events": lambda _p: self._core.clear_events(),
            "sim_inject": lambda _p: self._core.sim_inject(),
            "auto_calibrate": lambda _p: self._core.auto_calibrate(),
            "get_preview": lambda _p: self._core.get_preview(),
            "export_rpe": lambda _p: self._core.export_rpe(),
            "open_exports": lambda _p: self._core.open_exports(),
            "save_export": lambda p: self._save_export(p),
            # 自动制谱
            "autochart_start": lambda p: self._service().start(p),
            "autochart_status": lambda p: self._service().status(p),
            "autochart_cancel": lambda p: self._service().cancel(p),
            "autochart_snap": lambda p: self._service().snap(p),
            "autochart_undo": lambda p: self._service().undo(p),
            "save_artifact": lambda p: self._save_artifact(p),
            "pick_points": lambda _p: self._pick_points(),
        }

    def attach(self, window) -> None:
        self._window = window

    def _service(self):
        service = self._core.autochart
        if service is None:
            raise RuntimeError("自动制谱服务尚未初始化")
        return service

    # ------------------------------------------------------------------
    # JS 入口
    # ------------------------------------------------------------------
    def call(self, event: str, payload: dict | None = None) -> dict:
        handler = self._handlers.get(event or "")
        if handler is None:
            return {"ok": False, "error": f"未知操作：{event}"}
        if payload is not None and not isinstance(payload, dict):
            payload = {}
        try:
            if event in {"get_state", "save_settings", "audio_time", "audio_ready", "cover_clear",
                         "collector_pause", "collector_resume", "record_start", "record_stop",
                         "clear_events", "sim_inject", "auto_calibrate", "get_preview", "export_rpe"}:
                with self._core.lock:
                    result = handler(payload or {})
            else:
                result = handler(payload or {})
        except Exception as exc:  # 桥接层兜底，绝不让异常穿透到 WebView
            log.exception("处理界面请求失败：%s", event)
            return {"ok": False, "error": str(exc)}
        if not isinstance(result, dict):
            return {"ok": True, "data": result}
        return result

    # ------------------------------------------------------------------
    # 原生对话框
    # ------------------------------------------------------------------
    def _open_file(self, file_types: tuple[str, ...]) -> str | None:
        if self._window is None:
            return None
        try:
            selection = self._window.create_file_dialog(
                _dialog_kind("OPEN"), allow_multiple=False, file_types=file_types)
        except Exception:
            log.exception("打开文件对话框失败")
            return None
        return self._first_path(selection)

    def _save_file(self, default_name: str, file_types: tuple[str, ...], directory: str = "") -> str | None:
        if self._window is None:
            return None
        try:
            selection = self._window.create_file_dialog(
                _dialog_kind("SAVE"), directory=directory or "",
                save_filename=default_name, file_types=file_types)
        except Exception:
            log.exception("保存对话框失败")
            return None
        return self._first_path(selection)

    @staticmethod
    def _first_path(selection) -> str | None:
        if not selection:
            return None
        if isinstance(selection, str):
            return selection
        for item in selection:
            if item:
                return str(item)
        return None

    # ------------------------------------------------------------------
    # 具体操作
    # ------------------------------------------------------------------
    def _pick_audio(self) -> dict:
        path = self._open_file(AUDIO_FILTER)
        if path is None:
            return {"ok": True, "cancelled": True}
        if not path.lower().endswith(AUDIO_EXTENSIONS) or not os.path.isfile(path):
            return {"ok": False, "error": "不支持该音频格式"}
        return self._core.set_audio_file(path)

    def _pick_cover(self) -> dict:
        path = self._open_file(IMAGE_FILTER)
        if path is None:
            return {"ok": True, "cancelled": True}
        if os.path.splitext(path)[1].lower() not in COVER_EXT_MAP:
            return {"ok": False, "error": "请选择 png/jpg/gif/webp/bmp 图片"}
        return self._core.set_cover_file(path)

    def _pick_points(self) -> dict:
        path = self._open_file(JSON_FILTER)
        if path is None:
            return {"ok": True, "cancelled": True}
        try:
            with open(path, "r", encoding="utf-8") as handle:
                raw = json.load(handle)
        except (OSError, ValueError) as exc:
            return {"ok": False, "error": f"读取失败：{exc}"}
        try:
            from .autochart_engine import validate_points

            data = validate_points(raw)
        except Exception as exc:
            return {"ok": False, "error": f"points 格式不正确：{exc}"}
        return {"ok": True, "filename": os.path.basename(path), "data": data}

    def _save_export(self, payload: dict) -> dict:
        filename = os.path.basename(str(payload.get("filename") or ""))
        source = self._core.export_path(filename)
        if not source:
            self._core.toast("找不到导出文件，请重新导出", "error")
            return {"ok": False, "error": "找不到导出文件"}
        target = self._save_file(filename, PEZ_FILTER, directory=self._core.paths.export_dir)
        if target is None:
            return {"ok": True, "cancelled": True}
        try:
            if os.path.abspath(target) != os.path.abspath(source):
                shutil.copy2(source, target)
        except OSError as exc:
            self._core.toast(f"另存为失败：{exc}", "error")
            return {"ok": False, "error": str(exc)}
        self._core.toast(f"已另存为：{target}", "success")
        return {"ok": True, "path": target}

    def _save_artifact(self, payload: dict) -> dict:
        job_id = str(payload.get("job_id") or "")
        filename = os.path.basename(str(payload.get("filename") or ""))
        source = self._service().artifact_path(job_id, filename)
        if source is None:
            self._core.toast("产物已失效，请重新生成", "error")
            return {"ok": False, "error": "产物已失效"}
        filters = ZIP_FILTER if filename.endswith(".zip") else (
            JSON_FILTER if filename.endswith(".json") else PEZ_FILTER)
        target = self._save_file(filename, filters, directory=str(source.parent))
        if target is None:
            return {"ok": True, "cancelled": True}
        try:
            if os.path.abspath(target) != os.path.abspath(str(source)):
                shutil.copy2(source, target)
        except OSError as exc:
            self._core.toast(f"另存为失败：{exc}", "error")
            return {"ok": False, "error": str(exc)}
        self._core.toast(f"已另存为：{target}", "success")
        return {"ok": True, "path": target}

    # ------------------------------------------------------------------
    def cover_base64(self) -> str | None:
        """供窗口图标等场景复用（当前未使用，保留扩展点）。"""
        payload = self._core.cover_payload()
        if payload.get("ok"):
            return payload.get("data")
        return None


def encode_file(path: str) -> str:
    with open(path, "rb") as handle:
        return base64.b64encode(handle.read()).decode("ascii")