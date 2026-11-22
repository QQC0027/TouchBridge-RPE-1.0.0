import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch
import wave
import zipfile

from app.collector import CollectorProcess
from app.core import AppCore
from app.eventbus import EventBus, MAX_BATCH, MAX_QUEUE
from app.paths import Paths
from app.ui_bridge import UiBridge


class FakeCollector:
    def __init__(self):
        self.commands = []

    def send(self, command):
        self.commands.append(command)
        return True


class DesktopTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='touchbridge-desktop-')
        self.paths = Paths(self.directory.name)
        self.paths.ensure()
        self.bus = EventBus()
        self.core = AppCore(self.paths, self.bus)
        self.api = UiBridge(self.core, self.bus)

    def tearDown(self):
        self.directory.cleanup()

    def test_bus_batches_and_limits(self):
        for i in range(MAX_BATCH + 20):
            self.bus.publish('gesture_raw', {'address': '/move', 'args': [i]})
        for i in range(MAX_QUEUE + 20):
            self.bus.publish('state', {'value': i})
        events = self.bus.drain()
        self.assertEqual(len(events), MAX_QUEUE + 1)
        self.assertEqual(len(events[-1]['payload']), MAX_BATCH)
        self.assertEqual(events[-1]['event'], 'raw_batch')
        self.assertEqual(self.bus.dropped, 20)
        self.assertEqual(self.bus.pending(), 0)

    def test_record_gesture_clear_stop(self):
        fake = FakeCollector()
        self.core.set_collector(fake)
        self.assertTrue(self.api.call('record_start')['ok'])
        self.core.handle_gesture('/down', [1, .3, .4, 1, 0])
        self.core.handle_gesture('/tap', [1, .3, .4, 1, .05])
        self.core.handle_gesture('/up', [1])
        self.assertEqual(len(self.core.notes), 1)
        self.assertTrue(self.api.call('record_stop')['ok'])
        self.assertFalse(self.core.recording)
        self.assertIn('REC_STOP', fake.commands)
        self.api.call('clear_events')
        self.assertEqual(self.core.notes, [])
        self.assertEqual(fake.commands[-1], 'CLEAR')
        self.assertTrue(self.api.call('collector_pause')['ok'])
        self.assertTrue(self.core.collector_status['paused'])
        self.assertTrue(self.api.call('collector_resume')['ok'])
        self.assertFalse(self.core.collector_status['paused'])

    def test_import_export_and_persistence(self):
        audio = Path(self.directory.name) / '测试音频.wav'
        with wave.open(str(audio), 'wb') as wav:
            wav.setparams((1, 2, 22050, 0, 'NONE', ''))
            wav.writeframes(b'\x00\x00' * 22050)
        self.assertTrue(self.core.set_audio_file(str(audio))['ok'])
        self.assertTrue(self.core.audio_payload()['data'])
        self.assertTrue(self.api.call('sim_inject')['ok'])
        self.assertTrue(self.api.call('get_preview')['preview']['notes'])
        self.assertTrue(self.api.call('auto_calibrate')['ok'])
        result = self.api.call('export_rpe')
        self.assertTrue(result['ok'], result)
        with zipfile.ZipFile(result['path']) as package:
            self.assertTrue('info.txt' in package.namelist())
            self.assertTrue(any(name.endswith('.wav') for name in package.namelist()))
        self.assertTrue(self.api.call('save_settings', {'settings': {'bpm': 127}})['ok'])
        other = AppCore(self.paths, EventBus())
        self.assertEqual(other.config['settings']['bpm'], 127)
        self.assertTrue(other.public_state()['audio_loaded'])
        self.assertFalse(self.api.call('unknown')['ok'])
        self.assertNotIn('osc_port', other.public_state())

    def test_legacy_migration(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'backend' / 'uploads').mkdir(parents=True)
            (root / 'backend' / 'uploads' / 'song.wav').write_bytes(b'old audio')
            (root / 'backend' / 'config.json').write_text(json.dumps({'settings': {'bpm': 99}}))
            paths = Paths(folder)
            self.assertTrue(paths.ensure())
            self.assertEqual((Path(paths.upload_dir) / 'song.wav').read_bytes(), b'old audio')
            self.assertEqual(json.loads(Path(paths.config_path).read_text())['settings']['bpm'], 99)
            paths.ensure()
            self.assertEqual((Path(paths.upload_dir) / 'song.wav').read_bytes(), b'old audio')

    def test_collector_forced_exit_restores_settings(self):
        collector = CollectorProcess(self.core, 'TouchBridge.exe')
        process = MagicMock()
        process.returncode = 1
        process.wait.side_effect = [subprocess.TimeoutExpired('TouchBridge.exe', 2), 1]
        collector._process = process
        collector._alive = True
        with patch.object(collector, '_write', return_value=True), \
                patch('app.collector.subprocess.run') as restore:
            collector.stop()
        process.terminate.assert_called_once()
        restore.assert_called_once_with(
            ['TouchBridge.exe', '--restore'], stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
            timeout=5, check=True,
        )
        for stream in (process.stdin, process.stdout, process.stderr):
            self.assertTrue(stream.close.called)
        self.assertIsNone(collector._process)
        self.assertFalse(collector.alive)

    @unittest.skipUnless(Path('collector/dist/TouchBridge.exe').is_file(), '需先构建采集器')
    def test_collector_lifecycle(self):
        collector = CollectorProcess(self.core, str(Path('collector/dist/TouchBridge.exe').resolve()))
        self.core.set_collector(collector)
        try:
            self.assertTrue(collector.start())
            process = collector._process
            self.assertIsNotNone(collector.ping(timeout=10))
            self.assertTrue(collector.send('PAUSE'))
            limit = time.monotonic() + 3
            while not self.core.collector_status['paused'] and time.monotonic() < limit:
                time.sleep(.05)
            self.assertTrue(self.core.collector_status['paused'])
            self.assertTrue(collector.send('RESUME'))
            self.assertIsNotNone(collector.ping())
        finally:
            collector.stop()
        self.assertIsNotNone(process.poll())
        self.assertEqual(process.returncode, 0)
        self.assertFalse(collector.alive)


if __name__ == '__main__':
    unittest.main()
