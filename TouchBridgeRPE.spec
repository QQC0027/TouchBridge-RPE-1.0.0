from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, copy_metadata

root = Path(SPECPATH)
datas = [
    (str(root / 'ui'), 'ui'),
    (str(root / 'collector' / 'dist' / 'TouchBridge.exe'), 'collector/dist'),
    (str(root / 'third_party' / 'audio2pez'), 'third_party/audio2pez'),
    (str(root / 'LICENSE'), '.'),
    (str(root / 'AI_DISCLOSURE.md'), '.'),
]
datas += collect_data_files('librosa')
datas += collect_data_files('webview')
datas += copy_metadata('librosa')
hiddenimports = [
    'webview.platforms.edgechromium', 'webview.platforms.winforms',
    'librosa', 'librosa.core', 'librosa.feature', 'librosa.beat', 'librosa.onset',
    'numba', 'scipy', 'scipy.signal', 'scipy.ndimage', 'scipy.special',
    'soundfile', '_soundfile_data', 'audioread', 'sklearn',
    'sklearn.utils._cython_blas', 'PIL.Image',
]
a = Analysis(
    [str(root / 'main.py')], pathex=[str(root)], binaries=[], datas=datas,
    hiddenimports=hiddenimports, hookspath=[], hooksconfig={}, runtime_hooks=[],
    excludes=['flask', 'flask_socketio', 'socketio', 'engineio', 'werkzeug',
              'tkinter', 'matplotlib', 'pytest', 'IPython', 'PyQt5', 'PyQt6', 'PySide6'],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [], exclude_binaries=True, name='TouchBridgeRPE',
    debug=False, bootloader_ignore_signals=False, strip=False, upx=False,
    console=False, disable_windowed_traceback=False,
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name='TouchBridgeRPE')
