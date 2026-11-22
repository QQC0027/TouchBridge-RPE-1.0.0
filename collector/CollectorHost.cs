using System.Text.Json;
using System.Windows.Forms;
using TouchBridge.Core;

namespace TouchBridge;

/// <summary>
/// 一体化版本的无窗口宿主。
///
/// 界面全部由主程序（Python + WebView2）负责，这里只做三件事：
/// 1. 创建隐藏消息窗（Raw Input 与全局热键都需要窗口句柄）；
/// 2. 把触点帧喂给 <see cref="GestureEngine"/>，输出为 JSON 行；
/// 3. 按主程序指令控制「录制专注模式」（鼠标锁定 + 触控板手势临时禁用）。
/// </summary>
internal sealed class CollectorHost : ApplicationContext
{
    private const int WmHotkey = 0x0312;

    private readonly PipeChannel _pipe;
    private readonly TouchpadSource _source;
    private readonly GestureEngine _engine;
    private readonly RecordingGuard _guard = new();
    private readonly MessageWindow _window;
    private readonly System.Windows.Forms.Timer _statusTimer;
    private readonly object _statusLock = new();

    private string _lastStatus = string.Empty;
    private bool _paused;
    private long _lastFrameTicks;

    public CollectorHost(PipeChannel pipe)
    {
        _pipe = pipe;
        _window = new MessageWindow(this);
        _source = new TouchpadSource();
        _engine = new GestureEngine(pipe)
        {
            TrackpadOk = _source.HasTouchpad,
            DeviceSummary = _source.DeviceSummary,
        };

        _source.FrameReady += OnFrame;

        _statusTimer = new System.Windows.Forms.Timer { Interval = 500 };
        _statusTimer.Tick += (_, _) => PublishStatus();
        _statusTimer.Start();

        PublishStatus();
        _pipe.StartCommandLoop(OnCommand, OnInputClosed);
    }

    // ------------------------------------------------------------------
    // 触点 → 手势
    // ------------------------------------------------------------------
    private void OnFrame(ContactFrame frame)
    {
        _engine.TrackpadOk = _source.HasTouchpad;
        _engine.DeviceSummary = _source.DeviceSummary;
        _engine.Sending = !_paused;
        _engine.Tick(frame);
        if (_paused || frame.TimestampTicks - _lastFrameTicks < System.Diagnostics.Stopwatch.Frequency / 25) return;
        _lastFrameTicks = frame.TimestampTicks;
        _pipe.Event("frame", writer =>
        {
            writer.WriteNumber("devices", frame.DeviceCount);
            writer.WriteNumber("count", frame.Count);
            writer.WriteStartArray("contacts");
            foreach (TouchContact contact in frame.Contacts)
            {
                writer.WriteStartObject();
                writer.WriteNumber("id", contact.ContactId);
                writer.WriteNumber("x", contact.X);
                writer.WriteNumber("y", contact.Y);
                writer.WriteBoolean("tip", contact.Tip);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
        });
    }

    internal void OnHotkey(int id)
    {
        _guard.HandleHotkey(id);
        PublishStatus();
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------
    private void OnCommand(JsonElement command)
    {
        // 管道线程不能直接操作窗口与鼠标锁定，统一回到 UI 线程
        try
        {
            if (_window.IsDisposed) return;
            _window.BeginInvoke(new Action(() => Execute(command)));
        }
        catch (InvalidOperationException)
        {
        }
    }

    private void Execute(JsonElement command)
    {
        if (command.ValueKind != JsonValueKind.Object) return;
        string name = command.TryGetProperty("cmd", out JsonElement cmd) && cmd.ValueKind == JsonValueKind.String
            ? cmd.GetString() ?? string.Empty : string.Empty;

        switch (name.ToUpperInvariant())
        {
            case "REC_START":
                _guard.EnterRecording(_window.Handle);
                PublishStatus();
                break;

            case "REC_STOP":
                _guard.ExitRecording();
                PublishStatus();
                break;

            case "CLEAR":
                _engine.Reset();
                break;

            case "PAUSE":
                _paused = true;
                _engine.Reset();
                _engine.Sending = false;
                PublishStatus();
                break;

            case "RESUME":
                _paused = false;
                _engine.Sending = true;
                PublishStatus();
                break;

            case "PING":
                long id = command.TryGetProperty("id", out JsonElement idElement) && idElement.TryGetInt64(out long parsed) ? parsed : 0;
                _pipe.Event("pong", writer => writer.WriteNumber("id", id));
                break;

            case "QUIT":
                Shutdown();
                break;

            default:
                _pipe.Event("log", writer => writer.WriteString("message", $"unknown command: {name}"));
                break;
        }
    }

    private void OnInputClosed()
    {
        // 主程序退出（stdin 关闭）：立刻自行结束，避免留下孤儿进程
        try
        {
            if (_window.IsDisposed) return;
            _window.BeginInvoke(new Action(Shutdown));
        }
        catch (InvalidOperationException)
        {
        }
    }

    private void Shutdown()
    {
        if (_guard.State != RecordLockState.Idle)
        {
            _guard.ExitRecording();
        }
        _statusTimer.Stop();
        ExitThread();
    }

    // ------------------------------------------------------------------
    // 状态上报
    // ------------------------------------------------------------------
    private void PublishStatus()
    {
        lock (_statusLock)
        {
            string summary = _paused ? _source.DeviceSummary + "（已暂停）" : _source.DeviceSummary;
            string signature = $"{_source.DeviceCount}|{summary}|{_source.Diagnostics}|{_paused}|{_source.LastError}|{_guard.Warning}|{_guard.State}";
            if (signature == _lastStatus) return; // 无变化不刷屏
            _lastStatus = signature;

            _pipe.Event("status", writer =>
            {
                writer.WriteNumber("devices", _source.DeviceCount);
                writer.WriteString("summary", summary);
                writer.WriteString("diagnostics", _source.Diagnostics ?? string.Empty);
                writer.WriteBoolean("paused", _paused);
                writer.WriteBoolean("locked", _guard.State != RecordLockState.Idle);
                if (_source.LastError is null) writer.WriteNull("error");
                else writer.WriteString("error", _source.LastError);

                string? warning = _guard.Warning;
                if (string.IsNullOrEmpty(warning)) writer.WriteNull("warning");
                else writer.WriteString("warning", warning);
            });
        }
    }

    protected override void ExitThreadCore()
    {
        try
        {
            _guard.ExitRecording();
            _statusTimer.Stop();
            _statusTimer.Dispose();
            _source.FrameReady -= OnFrame;
            _source.Dispose();
            _window.Dispose();
        }
        finally
        {
            RecordingGuard.EmergencyRestore();
            base.ExitThreadCore();
        }
    }

    /// <summary>仅用于提供窗口句柄（Raw Input、WM_HOTKEY）的不可见控件。</summary>
    private sealed class MessageWindow : Control
    {
        private readonly CollectorHost _host;

        internal MessageWindow(CollectorHost host)
        {
            _host = host;
            // 强制创建句柄；控件永不显示
            _ = Handle;
        }

        protected override void WndProc(ref Message message)
        {
            if (message.Msg == WmHotkey)
            {
                _host.OnHotkey((int)(message.WParam.ToInt64() & 0xFFFF));
                return;
            }
            base.WndProc(ref message);
        }
    }
}