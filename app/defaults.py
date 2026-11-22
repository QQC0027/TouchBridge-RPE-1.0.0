"""默认配置。

改造后不再有 OSC / 控制端口字段：采集器是应用自己拉起的子进程，走管道通信。
"""

from __future__ import annotations

import copy

DEFAULT_SETTINGS: dict = {
    "bpm": 160,
    "offset_ms": 0,
    "beats_per_bar": 4,
    "subdivision": 8,
    "snap_tolerance": 0.6,
    "debounce_ms": 40,
    "x_mode": "coordinate",
    "regions": 9,
    "flick_speed": 2,
    "drag_sample_ms": 25,
    "deadZone": 0.03,
    "holdMinTime": 0.2,
    "flickWindow": 0.2,
    "flickMaxDist": 0.25,
    "segments": [],
    "dragMinSpeed": 0.25,
    "dragMaxDur": 1.5,
    "hit_offset_ms": 0,
}

DEFAULT_META: dict = {
    "name": "我的谱面",
    "charter": "",
    "composer": "",
    "level": "",
    "id": "",
    "background": "",
    "song": "",
    "cover": "",
}

DEFAULT_CONFIG: dict = {
    "autochart_enabled": True,
    "settings": DEFAULT_SETTINGS,
    "meta": DEFAULT_META,
}


def new_config() -> dict:
    """返回一份全新的默认配置副本（避免共享可变对象）。"""
    return copy.deepcopy(DEFAULT_CONFIG)


def normalise(config: dict) -> dict:
    """补齐缺失字段并剔除已废弃的端口配置。"""
    cfg = new_config()
    if not isinstance(config, dict):
        return cfg

    cfg["autochart_enabled"] = bool(config.get("autochart_enabled", True))
    settings = config.get("settings")
    if isinstance(settings, dict):
        cfg["settings"].update({k: v for k, v in settings.items() if k in cfg["settings"]})
    meta = config.get("meta")
    if isinstance(meta, dict):
        cfg["meta"].update({k: v for k, v in meta.items() if k in cfg["meta"]})
    return cfg