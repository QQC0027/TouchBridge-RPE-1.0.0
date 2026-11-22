# AI 参与声明（AI Disclosure）

本仓库 **TouchBridge → RPE（触控板制谱器）** 在开发与文档撰写过程中有 AI 实质性参与。本着透明原则，作如下声明。

## 1. AI 参与了什么

- **核心层（`app/`）**：把原 Web 服务的状态与路由迁移为进程内 `AppCore`，实现事件总线、配置与数据路径、谱包导出、文件对话框桥接、自动制谱任务与采集子进程管理。
- **采集器（`collector/`）**：把原 WinForms / OSC 采集器改为无窗口管道模式，`stdout` 输出 JSON 事件、`stdin` 接收命令；保留 Raw Input 注册与按 HID 报告描述符解析触点的核心逻辑。
- **界面（`ui/index.html`）**：保留原单页操作分区，用进程内桥替换 Socket.IO，改用原生文件对话框、`file://` / Blob 媒体与「另存为」导出。
- **工程化**：`main.py` 单窗口入口、`TouchBridgeRPE.spec`、`build_exe.bat`、`collector/build.bat`、依赖清单与多平台测试适配。
- **文档**：`README.md`、`AI_DISCLOSURE.md`、`.gitignore` 与发布说明整理。

## 2. AI 没有独立决定什么

- 项目目标、功能取舍、参数默认值、是否开源等均由作者决定。
- AI 生成的代码经过本机验证：算法回归、桌面核心测试、真实 WebView2 窗口诊断、采集管道握手/暂停/恢复/退出、谱包导出和五档自动制谱。
- 当前环境未检测到精确式触控板，真实多触点输入、热插拔与专注模式热键仍需在有设备的机器上确认。
- 任何系统接口与恢复逻辑的使用方式均由作者审阅确认。

## 3. 采集器与通信方式说明

`collector/` 通过 Windows 官方接口读取触控板，不依赖未公开符号：

1. `RegisterRawInputDevices` 注册 `UsagePage = 0x0D (Digitizer)` / `Usage = 0x05 (Touch Pad)`，从 `WM_INPUT` 获取 HID 原始报告；
2. `GetRawInputDeviceInfo(RIDI_PREPARSEDDATA)` 配合 `HidP_GetCaps` / `HidP_GetValueCaps` 按设备描述符建立字段映射；
3. `HidP_GetData` 逐帧解析 Contact ID / X / Y / Tip，归一化后交给手势状态机；
4. 采集结果通过标准输入输出管道传给主程序，不监听任何 TCP/UDP 端口，也不连接外部服务器；
5. 录制专注模式临时改写鼠标与触控板设置，采集器在正常退出、异常退出或收到恢复命令时恢复备份。

因此，本采集器只对遵循 Windows Precision Touchpad 规范的精确式触控板有效；老式 Synaptics / Elan 私有驱动触控板或多数外接设备不受支持。

## 4. 使用建议

AI 生成的代码不保证无 bug、无安全隐患或在所有环境可运行。使用前建议：

1. 审查关键代码，尤其是 P/Invoke 结构体布局、HID 报告解析与系统设置恢复；
2. 先在测试环境小规模试用；
3. 遇到机型或系统兼容问题，到仓库 Issues 反馈或提交 PR。

## 5. 责任归属

本项目以 MIT 许可证发布。根据 MIT 条款，软件按「现状」提供，作者与 AI 提供方均不对使用后果负责。AI 的参与不改变这一责任边界：最终的使用决策与风险由使用者承担。

---

*最后更新：2026-11-22*
