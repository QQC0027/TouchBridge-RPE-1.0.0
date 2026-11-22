import io
import json
import math
from pathlib import Path
import struct
import tempfile
import time
import unittest
import wave
import zipfile

from app.autochart_engine import generate_tiers, snap_align, validate_points
from app.autochart_service import AutochartService
from app.core import AppCore
from app.eventbus import EventBus
from app.paths import Paths


class AutoChartTest(unittest.TestCase):
    def test_points_validation(self):
        result = validate_points({"bpm": 120, "points": [{"time_sec": 1.0}, {"time_sec": 0.5}]})
        self.assertEqual([240 * 2, 480 * 2], [p["beat_tick"] for p in result["points"]])
        with self.assertRaises(ValueError):
            validate_points({"bpm": 1000, "points": [{"time_sec": 0.5}]})

    def test_snap_preserves_drag_and_zero_strength(self):
        notes = [{"t_sec": 0.55, "path": [(0.55, .1, .2), (0.72, .3, .4)]}, {"t_sec": 2.31}]
        points = [{"time_sec": 0.52, "probability": 1}]
        self.assertEqual(snap_align(notes, points, 120, strength=0), {"notes": notes, "moved": 0})
        result = snap_align(notes, points, 120, window_ms=45)
        self.assertEqual(result["moved"], 1)
        self.assertAlmostEqual(result["notes"][0]["t_sec"], 0.52)
        self.assertAlmostEqual(result["notes"][0]["path"][1][0], 0.69)
        self.assertEqual(result["notes"][1], notes[1])
        self.assertEqual(notes[0]["t_sec"], 0.55)

    def test_detection_and_seeded_five_tier_package(self):
        with tempfile.TemporaryDirectory(prefix="touchbridge-autochart-") as directory:
            root = Path(directory)
            audio = root / "pulse.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 22050, 0, "NONE", ""))
                samples = bytearray()
                for i in range(22050 * 10):
                    t = (i % 10336) / 22050
                    sample = int(27000 * math.exp(-70 * t) * math.sin(2 * math.pi * 560 * t)) if t < .08 else 0
                    samples.extend(struct.pack("<h", sample))
                wav.writeframes(samples)

            paths = Paths(str(root))
            paths.ensure()
            state = AppCore(paths, EventBus())
            self.assertTrue(state.set_audio_file(str(audio))["ok"])
            state.config["settings"]["bpm"] = 100
            state.notes = [{"t_sec": 0.53}]
            service = AutochartService(state, state.bus, paths.upload_dir, paths.export_dir)

            def wait(method, data):
                request = method(data)
                self.assertTrue(request["ok"], request)
                timeout = time.monotonic() + 120
                while time.monotonic() < timeout:
                    status = service.status({"job_id": request["job_id"]})
                    if status["status"] in ("done", "error", "cancelled"):
                        self.assertEqual(status["status"], "done", status)
                        while service.active is not None and time.monotonic() < timeout:
                            time.sleep(.01)
                        return status
                    time.sleep(.05)
                self.fail("生成超时")

            options = {"all_tiers": True, "line_fx": "mix", "seed": 42, "title": "测试歌曲", "artist": ""}
            status = wait(service.start, {"options": options})
            self.assertEqual(len(status["results"]), 5)
            batch_path = service.artifact_path(status["job_id"], status["batch_file"])
            with zipfile.ZipFile(batch_path) as batch:
                self.assertEqual(len(batch.namelist()), 5)
                for filename in batch.namelist():
                    self.assertTrue(filename.isascii())
                    with zipfile.ZipFile(io.BytesIO(batch.read(filename))) as pez:
                        pid = filename.removesuffix(".pez")
                        self.assertEqual(set(pez.namelist()), {"info.txt", "info.yml", pid + ".json", pid + ".wav", pid + ".png"})
                        chart = json.loads(pez.read(pid + ".json"))
                        self.assertEqual(chart["META"]["RPEVersion"], 160)
                        self.assertEqual(chart["META"]["song"], pid + ".wav")
                        self.assertIn("inclineEvents", chart["judgeLineList"][0]["extended"])
                        self.assertTrue(chart["BPMList"])
                        self.assertIn(b"\r\n", pez.read("info.txt"))
            points_path = service.artifact_path(status["job_id"], status["points_file"])
            points = json.loads(points_path.read_text(encoding="utf-8"))
            self.assertGreater(len(points["points"]), 5)
            first = generate_tiers(audio, options, points=points)
            second = generate_tiers(audio, options, points=points)
            self.assertEqual(first["charts"], second["charts"])
            single_options = {**options, "all_tiers": False, "difficulty": "hard"}
            single = generate_tiers(audio, single_options, points=points)
            self.assertEqual(first["charts"]["hard"], single["charts"]["hard"])
            packed = wait(service.start, {"options": single_options, "points": points})
            self.assertEqual(len(packed["results"]), 1)
            self.assertTrue(service.artifact_path(packed["job_id"], packed["results"][0]["file"]).is_file())
            self.assertIsNone(service.artifact_path(status["job_id"], "../config.json"))
            wait(service.snap, {"window_ms": 120, "strength": 1})
            self.assertNotEqual(state.config["settings"]["bpm"], 100)
            self.assertTrue(service.undo()["ok"])
            self.assertEqual(state.config["settings"]["bpm"], 100)
            self.assertEqual(state.notes, [{"t_sec": 0.53}])


if __name__ == "__main__":
    unittest.main()
