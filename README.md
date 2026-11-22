# TouchBridge → RPE（Windows 一体化桌面版）

把 Windows 精确式触控板当作打谱工具：在原生窗口里导入音乐、录制 Tap / Hold / Flick / Drag，实时预览并导出可被 Phira / PhiEdit 导入的 `.pez` 谱包；也可以自动生成单档谱面或五档 ZIP。

本版本只有一个入口：双击 `TouchBridgeRPE.exe`。不需要 `run.bat`、不需要浏览器、不需要命令行窗口，也不需要配置 OSC / 控制端口。

> 项目改编自 [MiGuo2787/TouchBridge-RPE](https://github.com/MiGuo2787/TouchBridge-RPE)，针对 Windows 精确式触控板重新实现了采集器与一体化桌面流程。

## 直接使用

1. 下载并完整解压发布包。
2. 双击 `TouchBridgeRPE.exe`（仓库构建产物为 `dist/TouchBridgeRPE/TouchBridgeRPE.exe`）。
3. 在唯一的应用窗口中导入音频、设置节拍、录制或自动生成谱面。

请保留整个发布目录，不要只复制 exe：同目录的 `_internal/` 是必要运行资源。

### 运行要求

- Windows 10 / 11，64 位。
- Microsoft Edge WebView2 Runtime。系统通常已安装；缺失时程序会弹出原生提示并给出安装地址。
- 真实触点录制需要 Windows 精确式触控板。没有触控板时仍可注入模拟手势、使用自动制谱和导出。
- 最终用户无需安装 Python、.NET SDK、Node.js 或浏览器插件。

程序不启动本地 HTTP 服务，不监听 TCP/UDP 端口，也不连接外部服务器。

## 界面与常用操作

窗口沿用原网页版分区：音频、节拍与量化、Flick 判定、采集连接状态、录制与导出、自动制谱、谱面预览。

### 音频、曲绘与节拍

- 点「导入音频」选择本地音乐，支持 WAV、MP3、OGG、FLAC、M4A、AAC；实际解码能力取决于系统。遇到问题可先转成 WAV 或 MP3。
- 播放器的播放时间是录制、预览和自动校准的共同时钟。
- 点「导入曲绘」选择图片；支持 PNG / JPEG / WebP / BMP / GIF，最大 10 MB。「清除曲绘」会改用自动生成的占位图。
- BPM、偏移、每小节拍数、吸附容差、去抖和采样补偿均可调。已有录制内容后可使用自动校准。
- Flick 强判据与快速模式可分别调整。

### 采集与录制

打开程序后会自动以隐藏子进程方式连接采集器。「采集连接状态」显示设备数、触点数和诊断信息，可暂停/恢复、重新连接、执行采集链路自检或注入模拟手势。

1. 导入并播放音频，设置节拍参数。
2. 点「开始录制」后再点播放，跟着音乐在触控板上做动作。
3. 点「停止录制」，查看预览，必要时校准或吸附对齐。
4. 点「导出 RPE」生成 `.pez`，可用结果区的「另存为…」复制到其他位置，或点「打开导出文件夹」。

采集状态区不再有端口输入框，也不需要点「开始监听 OSC」。原始手势消息会在「原始手势消息」区域显示。

### 录制专注模式

录制时采集器会固定并隐藏鼠标，并临时禁用三/四指触控板系统手势：

- `Esc`：暂时解除鼠标锁定，可以点击界面；录制不会停止。
- `Alt+Enter`：在仍录制时重新锁定鼠标。
- 停止录制或正常关闭窗口：恢复鼠标、热键与触控板设置。
- 若采集器异常退出或主程序被强制结束，下次启动采集器时会读取备份并恢复系统设置；主程序检测到异常退出时也会调用采集器的恢复入口。

锁定状态由采集器内部维护；警告和错误信息会显示在应用窗口的采集连接状态区，不再有独立采集器窗口。请尽量正常关闭窗口。

### 自动制谱

- 设置难度、轨道数、密度、随机种子、音符权重及轨道特效。
- 可生成单档 `.pez` 或五档 ZIP；显示进度并可取消。
- 可用原生文件对话框导入 `points.json`，也可由程序分析音频。
- 「吸附对齐」把已有录制音符对齐到音频踩音点，「撤销吸附」恢复上一次操作前的音符与节拍设置。
- 生成结果的保存按钮会打开系统「另存为」对话框。

## 数据、备份与迁移

所有可写数据都在 exe 同级的 `data/`，开发运行时则在仓库的 `data/`：

| 位置 | 内容 |
| --- | --- |
| `data/config.json` | 节拍、手势参数、谱面信息及功能开关 |
| `data/uploads/` | 导入的音频与曲绘副本 |
| `data/exports/` | 导出的谱包 |
| `data/exports/autochart/` | 自动制谱任务的谱包、ZIP 与 points 文件 |
| `data/logs/app.log` | 轮转日志，排查启动与采集错误 |
| `data/webview/` | WebView2 本地配置与缓存 |

升级时退出程序，替换程序文件并保留 `data/`。备份请复制整个 `data/`。原始录制音符仍是内存会话，请在关窗前导出；设置、媒体和已导出谱包会保留。

首次启动时，若同级仍有旧版 `backend/config.json`、`backend/uploads/`、`backend/exports/`，程序会将尚未存在的数据移动到 `data/` 并跳过同名项。迁移前建议先备份旧目录。迁移失败不会阻止启动，会记录日志并在界面提示。

## 开发与构建

仅开发者需要 Python 3.10+ x64 和 .NET 8 SDK。最终用户只需发布目录和 WebView2 Runtime。

- 一键构建：在仓库根目录运行 `build_exe.bat`。脚本建立/复用 `.venv`，安装 `requirements-build.txt`，发布自包含 C# 采集器，运行回归测试，再用 PyInstaller 构建 `dist/TouchBridgeRPE/`。覆盖前会暂存发行目录里的 `data/`，成功后恢复；失败时备份留在 `build/TouchBridgeRPE-data-backup/`。
- 开发运行：安装 `requirements.txt`，运行 `collector/build.bat /self-contained /quiet`，然后执行 `.venv/Scripts/python.exe main.py`。
- 引擎测试：`.venv/Scripts/python.exe tests/test_engine.py`。
- 桌面与自动制谱测试：`.venv/Scripts/python.exe -m unittest discover -s tests -v`。
- 完整窗口诊断：`TouchBridgeRPE.exe --smoke-test <结果文件的绝对路径>`。使用隔离数据验证音频、预览、管道、导出和五档自动制谱后自动退出，不进入录制专注模式。

入口是 `main.py`，不是 Web 服务。PyInstaller 配置在 `TouchBridgeRPE.spec`；运行依赖与构建依赖分别在 `requirements.txt` 和 `requirements-build.txt`。

### 项目结构

- `main.py`：单窗口入口、资源加载与生命周期。
- `app/`：状态、量化引擎、事件合批、媒体/导出、本地界面桥与采集子进程管理。
- `ui/index.html`：嵌入式界面，通过进程内 API 通信，不使用 Socket.IO。
- `collector/`：C# Raw Input / HID 采集器，隐藏运行，以 UTF-8 JSON 行管道通信。
- `third_party/audio2pez/`：音频分析与自动制谱算法。
- `tests/`：算法与桌面核心回归测试。
- `data/`：开发态数据、配置模板与从旧版迁移来的用户内容。

## 常见问题

- **窗口无法创建**：检查 WebView2 Runtime，确认完整解压发布目录，查看 `data/logs/app.log`。不要将程序放在禁止写入的系统目录。
- **没有触控板**：状态显示未检测到精确式触控板时，检查系统触控板设置与驱动。无设备时仍可使用模拟手势和自动制谱。
- **音频没有声音**：先导入再播放，检查系统音量。若系统解码不支持当前格式，转换为 WAV / MP3 后重试。
- **自动制谱耗时**：首次使用需要加载音频分析库，长音频更慢。请根据进度等待，必要时取消；取消不会改写已有录制内容。
- **异常后手势没有恢复**：重新打开再关闭应用，触发备份恢复；也可检查系统「触控板」设置。
- **升级后找不到媒体**：确认保留的是原来的整个 `data/`，而不只是 `config.json`。

## 已知限制

- 只支持 Windows 精确式触控板；老式私有驱动触控板和部分外接设备无法使用。
- 当前环境未检测到精确式触控板；真实多触点、热插拔、`Esc` / `Alt+Enter` 及系统手势恢复仍需在有设备的机器上确认。
- 某些触控板驱动不会立即响应手势设置变更，可能需要注销再登录。
- 若 `Esc` 或 `Alt+Enter` 被其他软件全局占用，快捷键可能注册失败；停止录制仍可恢复设置。

## 许可与说明

- 本项目按 [LICENSE](LICENSE) 分发，MIT 许可证。
- AI 参与情况见 [AI_DISCLOSURE.md](AI_DISCLOSURE.md)。
- 自动制谱算法的说明与许可见 `third_party/audio2pez/README.md` 和 `third_party/audio2pez/LICENSE`。
- 请只使用你有合法使用权的音乐制作谱面。
- 本工具与 Phigros、Phira、PhiEdit 及其开发方没有隶属或合作关系；相关名称仅用于说明文件格式兼容。
