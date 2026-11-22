"""隔离数据目录中的桌面集成诊断；不会启动录制专注模式。"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import struct
import threading
import time
import traceback
import wave


def run_smoke(window, core, collector, result_path):
    result = {"ok": False, "pid": os.getpid(), "checks": {}}

    def wait(predicate, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(.1)
        raise TimeoutError("桌面集成诊断等待超时")

    def check(name, condition):
        result["checks"][name] = bool(condition)
        if not condition:
            raise AssertionError(name)

    def workflow():
        try:
            check("window_loaded", window.events.loaded.wait(30))
            wait(lambda: window.evaluate_js("typeof bridge !== 'undefined' && bridge.connected"))
            check("no_http_server", window._server is None)
            check("collector_ping", collector.ping(timeout=10) is not None)
            result["collector_pid"] = collector._process.pid
            check("no_port_input", not window.evaluate_js("!!document.getElementById('oscPort')"))

            audio = Path(core.paths.data_dir) / 'diagnostic.wav'
            with wave.open(str(audio), 'wb') as wav:
                wav.setparams((1, 2, 22050, 0, 'NONE', ''))
                samples = bytearray()
                for i in range(22050 * 10):
                    t = (i % 11025) / 22050
                    sample = int(22000 * math.exp(-70 * t) * math.sin(2 * math.pi * 560 * t)) if t < .08 else 0
                    samples.extend(struct.pack('<h', sample))
                wav.writeframes(samples)
            check("audio_import", core.set_audio_file(str(audio))["ok"])
            wait(lambda: window.evaluate_js("audio.readyState >= 2"))
            window.run_js("audio.play()")
            wait(lambda: window.evaluate_js("audio.currentTime > .15"))
            window.run_js("audio.pause()")
            check("audio_playback", True)

            window.run_js("document.getElementById('simBtn').click()")
            wait(lambda: window.evaluate_js("Number(document.getElementById('evCount').textContent) > 0"))
            wait(lambda: window.evaluate_js("preview.notes.length > 0"))
            check("preview", True)
            window.run_js("document.getElementById('selftestBtn').click()")
            wait(lambda: core.self_test_ok)
            check("ui_selftest", True)
            window.run_js("document.getElementById('exportBtn').click()")
            wait(lambda: list(Path(core.paths.export_dir).glob('*.pez')))
            wait(lambda: window.evaluate_js("!!document.querySelector('#dl button')"))
            check("ui_export", True)

            job = core.autochart.start({"options": {"all_tiers": True, "seed": 42, "title": "诊断"}})
            check("autochart_started", job.get("ok"))
            wait(lambda: core.autochart.status({"job_id": job["job_id"]}).get("status") in ('done', 'error', 'cancelled'), 150)
            status = core.autochart.status({"job_id": job["job_id"]})
            result["autochart"] = status
            check("autochart_five_tiers", status["status"] == 'done' and len(status.get('results', [])) == 5)
            result["javascript_errors"] = window.evaluate_js("window.__tbErrors || []")
            check("javascript", not result["javascript_errors"])
            result["ok"] = True
        except Exception:
            import faulthandler
            result["error"] = traceback.format_exc()
            if core.autochart.active:
                result["autochart"] = core.autochart.status({"job_id": core.autochart.active})
            with open(str(result_path) + '.threads.log', 'w', encoding='utf-8') as stack_file:
                faulthandler.dump_traceback(file=stack_file, all_threads=True)
        finally:
            try:
                process = collector._process
                core.shutdown()
                collector.stop()
                result["collector_exited"] = process is not None and process.poll() is not None
                if not result["collector_exited"]:
                    result["ok"] = False
                Path(result_path).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            finally:
                window.destroy()

    threading.Thread(target=workflow, name='desktop-diagnostics', daemon=True).start()
