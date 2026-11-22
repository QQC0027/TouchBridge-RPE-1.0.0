from __future__ import annotations

import threading
import uuid


class AutoChartTasks:
    def __init__(self):
        self._lock = threading.RLock()
        self._items: dict[str, dict] = {}

    def create(self, sid: str, options: dict) -> str:
        job_id = uuid.uuid4().hex
        with self._lock:
            self._items[job_id] = {
                "sid": sid, "options": options, "status": "queued", "percent": 0,
                "stage": "排队中", "logs": [], "cancel": False, "results": [], "error": None,
            }
        return job_id

    def get(self, job_id: str) -> dict | None:
        with self._lock:
            item = self._items.get(job_id)
            return dict(item) if item else None

    def update(self, job_id: str, **values) -> dict | None:
        with self._lock:
            item = self._items.get(job_id)
            if not item:
                return None
            item.update(values)
            return dict(item)

    def append_log(self, job_id: str, message: str) -> dict | None:
        with self._lock:
            item = self._items.get(job_id)
            if not item:
                return None
            item["logs"].append(message)
            return dict(item)

    def cancel(self, job_id: str) -> bool:
        with self._lock:
            item = self._items.get(job_id)
            if not item or item["status"] in ("done", "error", "cancelled"):
                return False
            item["cancel"] = True
            return True


TASKS = AutoChartTasks()
