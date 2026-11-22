"""自动制谱服务（由原 ``autochart_routes.py`` 改写）。

改动只有「通信方式」：``socketio.emit(..., to=sid)`` 换成进程内事件总线，
``request.sid`` 换成固定的界面通道标识；任务调度从
``socketio.start_background_task`` 换成普通后台线程。制谱算法本身未改动。
"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import json
import logging
import re
import shutil
import threading
import zipfile

from .autochart_tasks import TASKS

log = logging.getLogger(__name__)

#: 单窗口应用只有一个界面通道
SID = "ui"

#: 任务产物允许的文件名（下载/另存时校验，防止路径穿越）
ARTIFACT_RE = re.compile(r"[A-Za-z0-9_]+\.(pez|zip|json)")
JOB_RE = re.compile(r"[a-f0-9]{32}")


class Cancelled(Exception):
    pass


class AutochartService:
    def __init__(self, core, bus, upload_dir: str, export_dir: str) -> None:
        self.core = core
        self.bus = bus
        self.root = Path(export_dir) / "autochart"
        self.uploads = Path(upload_dir).resolve()
        self.lock = threading.RLock()
        self.active: str | None = None

    # ------------------------------------------------------------------
    # 基础设施
    # ------------------------------------------------------------------
    @staticmethod
    def failure(message) -> dict:
        return {"ok": False, "error": str(message)}

    def enabled(self) -> bool:
        return bool(self.core.config.get("autochart_enabled", True))

    def uploaded(self, filename: str | None) -> Path | None:
        if not filename or any(mark in filename for mark in ("/", "\\")):
            return None
        candidate = (self.uploads / filename).resolve()
        try:
            inside = candidate.is_relative_to(self.uploads)
        except AttributeError:  # Python < 3.9
            inside = str(candidate).startswith(str(self.uploads))
        return candidate if inside and candidate.is_file() else None

    @staticmethod
    def public(job_id: str, item: dict) -> dict:
        return {"job_id": job_id,
                **{k: v for k, v in item.items() if k not in ("sid", "options", "cancel")}}

    def push(self, job_id: str, **fields) -> None:
        item = TASKS.update(job_id, **fields)
        if item is not None:
            self.bus.publish("autochart_progress", self.public(job_id, item))

    @staticmethod
    def checkpoint(job_id: str) -> None:
        item = TASKS.get(job_id)
        if item and item["cancel"]:
            raise Cancelled()

    def emit_state(self) -> None:
        self.core.emit_state()

    def enqueue(self, work) -> dict:
        with self.lock:
            if self.active is not None:
                return self.failure("已有制谱或吸附任务正在运行")
            job_id = self.active = TASKS.create(SID, {})

            def progress(percent, stage):
                self.checkpoint(job_id)
                TASKS.append_log(job_id, stage)
                self.push(job_id, status="running", percent=percent, stage=stage)

            def run():
                try:
                    result = work(job_id, progress)
                    self.push(job_id, status="done", percent=100, stage="已完成", **result)
                except Cancelled:
                    shutil.rmtree(self.root / job_id, ignore_errors=True)
                    self.push(job_id, status="cancelled", stage="已取消")
                except ImportError:
                    shutil.rmtree(self.root / job_id, ignore_errors=True)
                    log.exception("音频分析依赖不可用")
                    self.push(job_id, status="error", stage="处理失败",
                              error="音频分析依赖缺失，无法自动制谱")
                except Exception as exc:
                    shutil.rmtree(self.root / job_id, ignore_errors=True)
                    log.exception("自动制谱任务 %s 失败", job_id)
                    self.push(job_id, status="error", stage="处理失败", error=str(exc))
                finally:
                    with self.lock:
                        self.active = None

            threading.Thread(target=run, name=f"autochart-{job_id[:8]}", daemon=True).start()
            return {"ok": True, "job_id": job_id}

    # ------------------------------------------------------------------
    # 制谱
    # ------------------------------------------------------------------
    def start(self, data: dict) -> dict:
        if not self.enabled():
            return self.failure("自动制谱未启用")
        if not isinstance(data, dict) or not isinstance(data.get("options", {}), dict):
            return self.failure("制谱参数无效")

        audio = self.uploaded(getattr(self.core, "audio_filename", None))
        cover = self.uploaded(getattr(self.core, "cover_filename", None))
        if audio is None:
            return self.failure("请先导入音频")

        meta = self.core.config["meta"].copy()
        options = data.get("options", {}).copy()
        options.update(title=meta.get("name"), artist=meta.get("composer") or "",
                       charter=meta.get("charter") or "TouchBridge")
        points = data.get("points")

        def work(job_id, progress):
            from .autochart_engine import generate_tiers

            generated = generate_tiers(audio, options, progress, points=points)
            output = self.root / job_id
            output.mkdir(parents=True, exist_ok=True)
            results = []
            for tier, chart in generated["charts"].items():
                progress(95, f"正在打包 {tier.upper()}")
                chart_meta = chart["META"]
                chart_id = chart_meta["id"]
                filename, error = self.core.packer.build(
                    chart, chart_id, chart_id=chart_id, output_dir=str(output),
                    audio_source=str(audio), cover_source=str(cover) if cover else None,
                    duration=generated["analysis"]["duration_sec"], export_meta=chart_meta,
                )
                if not filename:
                    raise RuntimeError(error)
                results.append({
                    "tier": tier,
                    "level": chart_meta["level"],
                    "note_count": generated["counts"][tier],
                    "file": filename,
                    "job_id": job_id,
                })
            (output / "points.json").write_text(
                json.dumps(generated["analysis"], ensure_ascii=False, allow_nan=False, indent=2),
                encoding="utf-8")
            self.checkpoint(job_id)
            response = {"results": results, "points_file": "points.json"}
            if len(results) > 1:
                with zipfile.ZipFile(output / "charts.zip", "w") as archive:
                    for result in results:
                        self.checkpoint(job_id)
                        archive.write(output / result["file"], result["file"])
                response["batch_file"] = "charts.zip"
            self.checkpoint(job_id)
            return response

        return self.enqueue(work)

    def status(self, data: dict) -> dict:
        job_id = data.get("job_id") if isinstance(data, dict) else None
        item = TASKS.get(job_id) if isinstance(job_id, str) else None
        if not item or item["sid"] != SID:
            return self.failure("找不到该任务")
        return {"ok": True, **self.public(job_id, item)}

    def cancel(self, data: dict) -> dict:
        item = self.status(data)
        if not item["ok"]:
            return item
        return {"ok": True} if TASKS.cancel(item["job_id"]) else self.failure("该任务已结束")

    # ------------------------------------------------------------------
    # 吸附对齐
    # ------------------------------------------------------------------
    def snap(self, data: dict) -> dict:
        if not self.enabled():
            return self.failure("自动制谱未启用")
        core = self.core
        filename = core.audio_filename
        audio = self.uploaded(filename)
        if core.recording or not core.notes or audio is None:
            return self.failure("请先导入音频并完成录制")
        try:
            window = float(data.get("window_ms", 120))
            strength = float(data.get("strength", 1))
            if not 0 <= window <= 1000 or not 0 <= strength <= 1:
                raise ValueError()
        except (AttributeError, TypeError, ValueError):
            return self.failure("吸附参数无效")

        notes = deepcopy(core.notes)
        settings = core.config["settings"]
        timing = {key: deepcopy(settings.get(key)) for key in ("bpm", "offset_ms", "segments")}

        def work(job_id, progress):
            from .autochart_engine import detect, snap_align

            progress(5, "正在检测节拍和音符起点")
            analysis = detect(audio)
            progress(85, f"BPM {analysis['bpm']:.2f}，正在对齐手势")
            if not analysis["points"]:
                raise ValueError("没有检测到可供吸附的音符起点")
            aligned = snap_align(notes, analysis["points"], analysis["bpm"], window, strength,
                                analysis["offset_sec"])
            self.checkpoint(job_id)
            if strength and window:
                if (core.recording or core.notes != notes or filename != core.audio_filename
                        or any(settings.get(k) != v for k, v in timing.items())):
                    raise ValueError("录制或节拍参数已变化，请重新吸附")
                if core.autochart_undo is None:
                    core.autochart_undo = (notes, timing)
                core.notes = aligned["notes"]
                settings.update(bpm=analysis["bpm"],
                                offset_ms=round(analysis["offset_sec"] * 1000, 1), segments=[])
                core.rebuild_clock()
                core.save_config()
                self.emit_state()
            moved = aligned["moved"]
            TASKS.append_log(job_id, f"已吸附 {moved} 个手势")
            return {"moved": moved, "message": f"已吸附 {moved} 个手势"}

        return self.enqueue(work)

    def undo(self, _data: dict | None = None) -> dict:
        core = self.core
        with self.lock:
            if not self.enabled() or self.active is not None or core.recording or core.autochart_undo is None:
                return self.failure("当前没有可撤销的吸附")
            notes, timing = core.autochart_undo
            core.notes = notes
            core.config["settings"].update(timing)
            core.rebuild_clock()
            core.save_config()
            core.autochart_undo = None
        self.emit_state()
        return {"ok": True}

    # ------------------------------------------------------------------
    # 产物定位（供「另存为」使用）
    # ------------------------------------------------------------------
    def artifact_path(self, job_id: str, filename: str) -> Path | None:
        if not (isinstance(job_id, str) and JOB_RE.fullmatch(job_id)):
            return None
        if not (isinstance(filename, str) and ARTIFACT_RE.fullmatch(filename)):
            return None
        item = TASKS.get(job_id)
        if not item or item["status"] != "done":
            return None
        path = self.root / job_id / filename
        return path if path.is_file() else None

    def cleanup(self) -> None:
        """退出时把仍在排队/运行的任务标记取消（工作线程会自行清理产物）。"""
        with self.lock:
            if self.active:
                TASKS.cancel(self.active)