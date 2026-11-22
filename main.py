"""TouchBridge → RPE 一体化应用入口。

双击 ``TouchBridgeRPE.exe``（或开发态运行 ``python main.py``）即完成全部工作：

1. 解析/迁移数据目录，铺默认配置；
2. 拉起隐藏的 C# 采集子进程（管道通信，不占端口）；
3. 创建原生 WebView2 窗口并加载本地界面，不开 HTTP 服务、不用浏览器；
4. 关窗时结束采集子进程、恢复系统触控板设置、清理自动制谱任务。
"""

from __future__ import annotations

import ctypes
import logging
import logging.handlers
import os
import sys
import traceback

#: 让 WebView2 允许 file:// 页面加载本地音频/图片，并允许无用户手势播放
WEBVIEW2_ARGS = "--allow-file-access-from-files --autoplay-policy=no-user-gesture-required"

WEBVIEW2_DOWNLOAD = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"


def _setup_logging(log_path: str) -> None:
    handlers: list[logging.Handler] = [
        logging.handlers.RotatingFileHandler(log_path, maxBytes=1 << 20, backupCount=2,
                                             encoding="utf-8"),
    ]
    if not getattr(sys, "frozen", False):
        stream = logging.StreamHandler(sys.stdout)
        handlers.append(stream)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s | %(message)s",
        handlers=handlers,
        force=True,
    )


def _message_box(text: str, title: str = "TouchBridge → RPE", icon: int = 0x10) -> None:
    """原生提示框（窗口建立失败时唯一可用的反馈方式）。"""
    try:
        ctypes.windll.user32.MessageBoxW(None, text, title, icon)
    except Exception:
        print(f"{title}: {text}", file=sys.stderr)


def main() -> int:
    from app.paths import Paths

    smoke_result = None
    temporary_data = None
    if "--smoke-test" in sys.argv:
        import tempfile
        index = sys.argv.index("--smoke-test")
        smoke_result = os.path.abspath(sys.argv[index + 1] if len(sys.argv) > index + 1 else "webview-smoke.json")
        temporary_data = tempfile.TemporaryDirectory(prefix="touchbridge-webview-")
    paths = Paths(temporary_data.name if temporary_data else None)
    if not os.path.isfile(paths.ui_html):
        _message_box(f"界面文件缺失：\n{paths.ui_html}\n\n请重新解压完整程序目录后重试。")
        return 1

    notices = paths.ensure()
    _setup_logging(paths.log_path)
    log = logging.getLogger("main")
    log.info("启动：base=%s resource=%s", paths.base_dir, paths.resource_dir)

    os.environ.setdefault("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", WEBVIEW2_ARGS)

    try:
        import webview
    except Exception as exc:
        log.exception("pywebview 不可用")
        _message_box(f"界面组件加载失败：{exc}\n\n请重新安装程序。")
        return 1

    from app.autochart_service import AutochartService
    from app.collector import CollectorProcess
    from app.core import AppCore
    from app.eventbus import EventBus
    from app.ui_bridge import UiBridge

    bus = EventBus()
    core = AppCore(paths, bus)
    core.autochart = AutochartService(core, bus, paths.upload_dir, paths.export_dir)
    collector = CollectorProcess(core, paths.collector_exe)
    core.set_collector(collector)
    bridge = UiBridge(core, bus)

    for notice in notices:
        core.toast(notice, "info")

    with open(paths.ui_html, encoding="utf-8") as html_file:
        html = html_file.read()
    if smoke_result:
        html = html.replace('<head>', '<head><script>window.__tbErrors=[];window.addEventListener("error",e=>window.__tbErrors.push(e.message));window.addEventListener("unhandledrejection",e=>window.__tbErrors.push(String(e.reason)));</script>', 1)
    window = webview.create_window(
        "TouchBridge → RPE",
        html=html,
        js_api=bridge,
        width=1440,
        height=940,
        min_size=(1120, 700),
        background_color="#0b0f17",
    )
    bridge.attach(window)

    collector.start()
    if smoke_result:
        from app.diagnostics import run_smoke
        run_smoke(window, core, collector, smoke_result)

    exit_code = 0
    try:
        webview.start(gui="edgechromium", http_server=False,
                      debug=bool(os.environ.get("TB_DEBUG")),
                      private_mode=False,
                      storage_path=os.path.join(paths.data_dir, "webview"))
    except Exception as exc:
        log.exception("窗口启动失败")
        _message_box(
            "无法创建应用窗口。\n\n"
            "最常见原因是本机缺少 Microsoft Edge WebView2 运行时。\n"
            f"请安装后重试：{WEBVIEW2_DOWNLOAD}\n\n"
            f"详细信息：{exc}"
        )
        exit_code = 1
    finally:
        log.info("正在退出并清理…")
        try:
            core.shutdown()
            if core.autochart is not None:
                core.autochart.cleanup()
        except Exception:
            log.exception("关闭核心层时出错")
        try:
            collector.stop()
        except Exception:
            log.exception("停止采集器时出错")
        log.info("已退出")
        if temporary_data is not None:
            import time
            logging.shutdown()
            for attempt in range(10):
                try:
                    temporary_data.cleanup()
                    break
                except OSError:
                    time.sleep(.2)
    return exit_code


def _excepthook(exc_type, exc_value, exc_tb) -> None:
    logging.getLogger("main").error(
        "未捕获异常：%s", "".join(traceback.format_exception(exc_type, exc_value, exc_tb)))
    _message_box(f"程序遇到未处理的错误：\n{exc_value}\n\n详情见 data/logs/app.log")


if __name__ == "__main__":
    sys.excepthook = _excepthook
    sys.exit(main())