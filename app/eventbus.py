"""进程内事件总线，替代原来的 ``socketio.emit``。

设计要点：

* **线程安全**：采集线程、自动制谱工作线程都会投递事件，因此只做加锁入队，
  绝不在工作线程里操作 WebView（Edge/WebView2 要求脚本求值发生在 UI 线程）。
* **合批**：触点报文（``osc_raw``）在录制时可达 ~83Hz，若逐条推给界面会造成
  大量跨进程序列化。这里把它们放进环形缓冲，由界面每 50ms 拉取时合并成一批。
* **拉取式下发**：界面通过 ``poll_events`` 主动取事件，简单且不依赖
  ``evaluate_js`` 的线程模型。
"""

from __future__ import annotations

import threading
from collections import deque

#: 高频事件：合并成一批下发，不逐条排队
BATCH_EVENTS = frozenset({"gesture_raw"})

#: 低频事件队列上限，超出后丢弃最旧的（宁可丢状态刷新，也不让界面卡死）
MAX_QUEUE = 512

#: 单批最多保留的触点报文条数（监视器只显示最近若干条）
MAX_BATCH = 240


class EventBus:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._queue: deque[dict] = deque()
        self._batch: deque[dict] = deque(maxlen=MAX_BATCH)
        self._dropped = 0

    def publish(self, name: str, payload: dict | None = None) -> None:
        """投递一条事件；可从任意线程调用。"""
        with self._lock:
            if name in BATCH_EVENTS:
                self._batch.append({"address": (payload or {}).get("address"),
                                    "args": (payload or {}).get("args") or []})
                return
            if len(self._queue) >= MAX_QUEUE:
                self._queue.popleft()
                self._dropped += 1
            self._queue.append({"event": name, "payload": payload if payload is not None else {}})

    def drain(self) -> list[dict]:
        """取走全部待发事件（界面拉取时调用）。"""
        with self._lock:
            events = list(self._queue)
            self._queue.clear()
            if self._batch:
                events.append({"event": "raw_batch", "payload": list(self._batch)})
                self._batch.clear()
        return events

    def pending(self) -> int:
        with self._lock:
            return len(self._queue) + (1 if self._batch else 0)

    @property
    def dropped(self) -> int:
        with self._lock:
            return self._dropped

    def reset(self) -> None:
        with self._lock:
            self._queue.clear()
            self._batch.clear()