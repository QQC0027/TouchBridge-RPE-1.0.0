# AI 参与声明（AI Disclosure）

本仓库 **TouchBridge → RPE（触控板制谱器）** 在开发与文档撰写过程中有 AI 实质性参与。本着透明原则，作如下声明。

## 1. AI 参与了什么

- **后端（`backend/`）**：手势分类、节拍量化、RPE/PEZ 构建、OSC 监听、Web 接口等代码，由 AI 智能体在作者需求描述下生成，并经作者本地运行、调参与修正。
- **前端（`frontend/`）**：可视化预览页面、录制 / 导出交互等，由 AI 生成。
- **采集器（`TouchBridge/`）**：从 macOS 私有框架实现迁移到 Windows 的 C# / .NET 8 / WinForms 版本，包括 Raw Input 注册、HID 报告描述符解析、手势状态机移植、OSC 封包与界面绘制，由 AI 依据 Microsoft 公开文档生成。
- **文档**：本仓库的 `README.md`、`AI_DISCLOSURE.md`、`.gitignore`、`requirements.txt` 整理、`run.bat` / `build.bat` 等工程脚本由 AI 撰写或辅助撰写。
- **工程化**：仓库结构规划、MIT 许可证选择建议、开源发布流程由 AI 协助。

## 2. AI 没有独立决定什么

- **项目目标、功能取舍、参数默认值、是否开源** 等均由**作者**拍板；
- AI 生成的代码在发布前经过**作者本机运行验证**（含采集器的 `dotnet build`、`--osctest` 的 OSC 报文解码核对、`--diagnose` 的设备描述符输出、150% DPI 下的界面检查，以及后端的单元测试与导出验证）；
- 涉及系统接口调用（Windows Raw Input / HID API）与本地网络监听（`127.0.0.1`，不外连）的部分，由作者确认其仅在本地生效。

## 3. 触控板采集器的特别说明

`TouchBridge/` 通过 Windows **官方接口** 读取触控板，不依赖任何未公开符号：

1. `RegisterRawInputDevices` 注册 `UsagePage = 0x0D (Digitizer)` / `Usage = 0x05 (Touch Pad)`，从 `WM_INPUT` 拿到 HID 原始报告；
2. `GetRawInputDeviceInfo(RIDI_PREPARSEDDATA)` + `HidP_GetCaps` / `HidP_GetValueCaps` / `HidP_GetButtonCaps` **按设备描述符**建立字段映射，因此不绑定某一款触控板的报告布局；
3. `HidP_GetData` 逐帧解析出 Contact ID / X / Y / Tip，归一化后交给手势状态机；
4. 报告格式遵循 Microsoft 公开的 **Windows Precision Touchpad** 规范（含 Hybrid / Parallel 上报模式的差异处理）。

因此，本采集器**只对遵循 WPT 规范的精确式触控板有效**；老式 Synaptics / Elan 私有驱动触控板不上报标准数字化仪报告，属于已知限制而非缺陷隐瞒。

## 4. 使用建议

AI 生成的代码**不保证无 bug、无安全隐患、在所有环境可运行**。在你自己的机器上使用前，建议：

1. **Review 关键代码**，尤其是 P/Invoke 结构体布局、报告解析与本地网络监听部分；
2. 先在测试环境小规模试用，确认行为符合预期；
3. 遇到机型 / 系统兼容问题，到仓库 [Issues](../../issues) 反馈，或自行修改后提 PR。

## 5. 责任归属

本项目以 **MIT 许可证** 发布。根据 MIT 条款，软件按「现状」提供，作者与 AI 提供方均不对使用后果负责。AI 的参与不改变这一责任边界：**最终的使用决策与风险由使用者承担**。

---

*最后更新：2026-11-20*
