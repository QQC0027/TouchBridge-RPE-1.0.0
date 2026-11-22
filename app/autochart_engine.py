from __future__ import annotations

import hashlib
import random
import re
import threading
from pathlib import Path

from third_party.audio2pez import audio_analyze, charter, config, detector, generate, packager

_GENERATION_LOCK = threading.RLock()


def safe_chart_id(name: str, tier: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_") or "song"
    digest = hashlib.md5(name.encode("utf-8")).hexdigest()[:8]
    return f"{slug[:24]}_{digest}_{tier}"


def _line_fx_config(options: dict, sections: list) -> dict | None:
    mode = options.get("line_fx", "none")
    swing = bool(options.get("main_swing", False))
    main_y = float(options.get("main_y", -300))
    if mode == "none" and (swing or main_y != -300):
        return {"mode": "basic", "sections": sections, "main_y": main_y, "main_swing": swing}
    return generate._build_line_fx(mode, sections, options.get("pulse_style", "chain"),
                                   options.get("deco_when", "intro_chorus"), main_y,
                                   options.get("mix_spec", ""), swing, options.get("sweep_flick_only", False))


def validate_points(data) -> dict:
    if not isinstance(data, (list, dict)):
        raise ValueError("踩音文件必须包含 points 数组")
    points = data.get("points") if isinstance(data, dict) else data
    if not isinstance(points, list) or not points or len(points) > 100_000:
        raise ValueError("踩音点数量无效")
    result = generate.coerce_points_dict(data)
    if not 20 <= result["bpm"] <= 400 or not 0 < result["duration_sec"] <= 7200:
        raise ValueError("踩音文件的 BPM 或时长无效")
    if not 1 <= result["ticks_per_beat"] <= 9600 or not -60 <= result["offset_sec"] <= 60:
        raise ValueError("踩音文件的拍刻度或偏移无效")
    for point in result["points"]:
        if not 0 <= point["time_sec"] <= result["duration_sec"] or not -9600 <= point["beat_tick"] <= 500_000_000 or not -10 <= point["beat"] <= 50_000:
            raise ValueError("踩音点超出有效范围")
    for tempo in result["tempo_events"]:
        if not 20 <= float(tempo["bpm"]) <= 400 or not 0 <= int(tempo["beat_tick"]) <= 500_000_000:
            raise ValueError("变速事件无效")
    result["points"].sort(key=lambda point: point["beat_tick"])
    return result


def detect(audio_path):
    return detector.detect_result(str(audio_path))


def generate_tiers(audio_path: str | Path, options: dict, progress=lambda percent, text: None, points=None) -> dict:
    """Run the vendored analysis and charter, returning chart JSON without packaging."""
    audio_path = str(audio_path)
    opts = dict(options)
    seed = int(opts.get("seed", 42))
    with _GENERATION_LOCK:
        old_random = charter.random
        try:
            progress(5, "正在检测节拍与音符起点")
            result = validate_points(points) if points is not None else detector.detect_result(audio_path, bpm=opts.get("bpm"))
            progress(24, f"检测到 {len(result['points'])} 个踩音点，BPM {result['bpm']:.2f}")
            audio = audio_analyze.analyze_audio(audio_path, result["bpm"], result["duration_sec"]) if audio_path else generate.fallback_audio(result["points"], result["bpm"], result["duration_sec"])
            progress(42, f"已分析 {len(audio['sections'])} 个音乐段落")
            tiers = list(config.DIFFICULTY_PRESETS) if opts.get("all_tiers") else [opts.get("difficulty", "hard")]
            charts, counts = {}, {}
            for index, tier in enumerate(tiers):
                if tier not in config.DIFFICULTY_PRESETS:
                    raise ValueError(f"未知难度档位: {tier}")
                presets = dict(config.DIFFICULTY_PRESETS[tier])
                key_mode = opts.get("key_mode", "auto")
                lanes = {"2k": config.LANES_2, "4k": config.LANES_4, "6k": config.LANES_6}.get(key_mode)
                if lanes is not None:
                    presets["lanes"] = lanes
                for key in ("tap_weight", "hold_weight", "flick_weight", "drag_weight"):
                    value = opts.get(key)
                    if value is not None:
                        presets[key] = float(value)
                if opts.get("hold_weight") is not None and float(opts["hold_weight"]) <= .01:
                    presets["disable_hold"] = True
                if opts.get("drag_weight") is not None and float(opts["drag_weight"]) <= .01:
                    presets["disable_drag"] = True
                charter.random = random.Random(seed)
                arranger = charter.Charter(
                    points=result["points"], bpm=result["bpm"], ticks_per_beat=result["ticks_per_beat"],
                    bands=audio["bands"], onset_norm=audio["onset_norm"], sections=audio["sections"],
                    difficulty=presets, rms_norm=audio["rms_norm"], density_mult=float(opts.get("density", 1.0)),
                )
                notes = arranger.generate()
                name = (opts.get("title") or Path(audio_path).stem).strip() or "chart"
                ext = Path(audio_path).suffix.lower() or ".mp3"
                chart_id = safe_chart_id(name, tier)
                meta = {
                    "id": chart_id,
                    "name": name,
                    "level": f"{presets['label']} {presets['level']}",
                    "charter": opts.get("charter", "TouchBridge"),
                    "composer": opts.get("artist", "Unknown"),
                    "illustration": "",
                    "song": chart_id + ext,
                    "background": chart_id + ".png",
                    "offset": int(round(result["offset_sec"] * 1000)),
                    "duration": result["duration_sec"],
                }
                fx_cfg = _line_fx_config(opts, audio["sections"])
                charts[tier] = packager.build_chart_json(
                    notes, result["bpm"], meta, result["tempo_events"], result["ticks_per_beat"], fx_cfg
                )
                counts[tier] = len(notes)
                progress(48 + int(46 * (index + 1) / len(tiers)), f"{presets['label']} {presets['level']}：{len(notes)} 个音符")
            return {"charts": charts, "counts": counts, "analysis": result, "seed": seed}
        finally:
            charter.random = old_random


def snap_align(gestures: list[dict], points: list[dict], bpm: float, window_ms: float = 120.0, strength: float = 1.0, offset_sec: float = 0.0) -> dict:
    from bisect import bisect_left, bisect_right

    window = max(0.0, window_ms) / 1000.0
    copies = [dict(note) for note in gestures]
    if not points or window == 0 or strength <= 0:
        return {"notes": copies, "moved": 0}
    ordered = sorted((point["time_sec"], point.get("probability", 0.4)) for point in points)
    times = [time for time, _ in ordered]
    beat = 60.0 / bpm
    moved = 0
    for item in copies:
        key = "t_sec" if "t_sec" in item else "time_sec"
        if item.get(key) is None:
            continue
        timestamp = item[key]
        begin = bisect_left(times, timestamp - window)
        end = bisect_right(times, timestamp + window)
        candidates = ordered[begin:end]
        grid = offset_sec + round((timestamp - offset_sec) / beat) * beat
        if grid >= 0 and abs(grid - timestamp) <= window:
            candidates.append((grid, 0.5))
        if not candidates:
            continue
        target, _ = min(candidates, key=lambda p: (abs(p[0] - timestamp) / (0.75 + 0.25 * p[1]), p[0]))
        delta = (target - timestamp) * min(1.0, strength)
        if abs(delta) <= 1e-9:
            continue
        item[key] = timestamp + delta
        if item.get("end_sec") is not None:
            item["end_sec"] += delta
        if item.get("path"):
            item["path"] = [(t + delta, x, y) for t, x, y in item["path"]]
        moved += 1
    return {"notes": copies, "moved": moved}
