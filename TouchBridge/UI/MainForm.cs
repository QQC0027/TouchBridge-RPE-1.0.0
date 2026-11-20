using System.Drawing.Drawing2D;
using TouchBridge.Core;

namespace TouchBridge.UI;

public sealed class MainForm : Form
{
    private readonly OscSender _sender;
    private readonly GestureEngine _engine;
    private readonly TouchpadSource _source;
    private readonly TouchPanel _panel = new();
    private readonly StatusHeader _header = new();
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 33 };
    private readonly Button _sendButton = new();
    private readonly Button _clearButton = new();
    private readonly Button _applyButton = new();
    private readonly TextBox _portBox = new();
    private readonly Label _portLabel = new();
    private readonly RecordingGuard _guard = new();
    private ControlReceiver? _receiver;
    private string? _controlError;
    private int _sendFailuresSeen;

    public MainForm()
    {
        _sender = new OscSender(OscSender.DefaultPort);
        _engine = new GestureEngine(_sender);
        _source = new TouchpadSource();
        _source.FrameReady += OnFrameReady;

        try
        {
            _receiver = new ControlReceiver();
            _receiver.CommandReceived += OnControlCommand;
        }
        catch (Exception ex)
        {
            _controlError = "控制端口监听失败";
            _engine.Note($"控制端口 {ControlReceiver.DefaultPort} 监听失败：{ex.Message}");
        }

        Text = "触控板 → RPE";
        BackColor = TouchPanel.Pal.Bg;
        ForeColor = TouchPanel.Pal.Text;
        Font = new Font("Microsoft YaHei UI", 9f);
        // 自绘界面统一按 96 DPI 设计，运行时用 UiScale 等比放大（字体以磅为单位会自动跟随 DPI）
        AutoScaleMode = AutoScaleMode.None;
        ClientSize = new Size(680, 590);
        MinimumSize = new Size(620, 540);
        StartPosition = FormStartPosition.CenterScreen;
        DoubleBuffered = true;

        BuildLayout();
        _timer.Tick += (_, _) => Refresh30Hz();
        _timer.Start();
    }

    /// <summary>窗口所在显示器的实际 DPI 相对 96 的倍率。</summary>
    private float UiScale => UiMetrics.ScaleOf(this);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyScale();
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        // 句柄创建时窗口可能还在默认显示器上，真正定位后再校正一次
        ApplyScale();
        // 否则端口输入框会在启动时全选高亮，看起来像被误触
        ActiveControl = null;
        _portBox.DeselectAll();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        ApplyScale();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.NativeMethods.WmHotkey)
        {
            _guard.HandleHotkey((int)(m.WParam.ToInt64() & 0xFFFF));
            return;
        }
        base.WndProc(ref m);
        if (m.Msg == Native.NativeMethods.WmDpiChanged) ApplyScale();
    }

    /// <summary>把 96 DPI 下的设计尺寸换算到当前 DPI。</summary>
    private void ApplyScale()
    {
        float s = UiScale;
        _header.Height = (int)(96 * s);
        _sendButton.SetBounds((int)(24 * s), (int)(56 * s), (int)(120 * s), (int)(32 * s));
        _clearButton.SetBounds((int)(156 * s), (int)(56 * s), (int)(76 * s), (int)(32 * s));
        _portLabel.SetBounds((int)(252 * s), (int)(64 * s), _portLabel.Width, _portLabel.Height);
        _portBox.SetBounds((int)(292 * s), (int)(58 * s), (int)(74 * s), (int)(26 * s));
        _applyButton.SetBounds((int)(374 * s), (int)(56 * s), (int)(76 * s), (int)(32 * s));
        _panel.Padding = new Padding((int)(24 * s), 0, (int)(20 * s), 0);
        ClientSize = new Size((int)(680 * s), (int)(590 * s));
        MinimumSize = new Size((int)(620 * s), (int)(540 * s));
    }

    private void BuildLayout()
    {
        SuspendLayout();

        _header.Dock = DockStyle.Top;
        _header.Height = 96;
        _header.BackColor = Color.FromArgb(15, 15, 20);

        _panel.Dock = DockStyle.Fill;
        _panel.Padding = new Padding(24, 0, 20, 0);

        Controls.Add(_panel);
        Controls.Add(_header);

        _sendButton.Text = "暂停发送";
        _sendButton.SetBounds(24, 56, 120, 32);
        StyleButton(_sendButton);
        _sendButton.Click += (_, _) => ToggleSending();

        _clearButton.Text = "清空";
        _clearButton.SetBounds(156, 56, 76, 32);
        StyleButton(_clearButton);
        _clearButton.Click += (_, _) =>
        {
            _engine.Reset();
            _engine.Note("已清空槽位与日志");
        };

        var portLabel = _portLabel;
        portLabel.Text = "端口";
        portLabel.ForeColor = TouchPanel.Pal.Dim;
        portLabel.AutoSize = true;
        portLabel.Location = new Point(252, 64);

        _portBox.Text = OscSender.DefaultPort.ToString();
        _portBox.SetBounds(292, 58, 74, 26);
        _portBox.TextAlign = HorizontalAlignment.Center;
        _portBox.BackColor = Color.FromArgb(28, 28, 36);
        _portBox.ForeColor = TouchPanel.Pal.Text;
        _portBox.BorderStyle = BorderStyle.FixedSingle;
        _portBox.Font = new Font("Consolas", 10.5f);
        _portBox.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                ApplyPort();
            }
        };

        _applyButton.Text = "应用";
        _applyButton.SetBounds(374, 56, 76, 32);
        StyleButton(_applyButton);
        _applyButton.Click += (_, _) => ApplyPort();

        _header.Controls.AddRange(new Control[] { _sendButton, _clearButton, portLabel, _portBox, _applyButton });

        ResumeLayout(false);
    }

    private static void StyleButton(Button button)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderColor = Color.FromArgb(255, 255, 255).WithAlpha(0.16f);
        button.FlatAppearance.MouseOverBackColor = Color.FromArgb(40, 40, 52);
        button.BackColor = Color.FromArgb(28, 28, 36);
        button.ForeColor = TouchPanel.Pal.Text;
        button.TabStop = true;
        button.UseVisualStyleBackColor = false;
    }

    private void ToggleSending()
    {
        _engine.Sending = !_engine.Sending;
        _sendButton.Text = _engine.Sending ? "暂停发送" : "开始发送";
        _engine.Note(_engine.Sending ? $"恢复发送 → 127.0.0.1:{_sender.Port}" : "已暂停发送");
    }

    private void ApplyPort()
    {
        if (!int.TryParse(_portBox.Text.Trim(), out int port) || port < 1 || port > 65535)
        {
            _engine.Note("端口无效，应为 1-65535");
            _portBox.Text = _sender.Port.ToString();
            return;
        }
        if (_sender.Open(port))
        {
            _engine.Note($"端口切换 → 127.0.0.1:{port}");
        }
        else
        {
            _engine.Note("端口切换失败，请检查端口是否可用");
            _portBox.Text = _sender.Port.ToString();
        }
    }

    private void OnFrameReady(ContactFrame frame)
    {
        _engine.Tick(frame);
    }

    /// <summary>控制指令来自后台 UDP 线程，切回 UI 线程后再操作 Win32 与控件。</summary>
    private void OnControlCommand(string command)
    {
        if (!IsHandleCreated || IsDisposed) return;
        try
        {
            BeginInvoke(new Action(() =>
            {
                if (IsDisposed) return;
                if (command == "REC_START")
                {
                    _guard.EnterRecording(Handle);
                    _engine.Note("录制专注：鼠标已隐藏并锁定；ESC 解除，Alt+Enter 重新锁定");
                    if (_guard.Warning is not null) _engine.Note(_guard.Warning);
                }
                else if (command == "REC_STOP")
                {
                    _guard.ExitRecording();
                    _engine.Note("已退出录制专注，系统设置已恢复");
                }
            }));
        }
        catch (ObjectDisposedException)
        {
        }
        catch (InvalidOperationException)
        {
        }
    }

    private void Refresh30Hz()
    {
        _engine.TrackpadOk = _source.HasTouchpad;
        _engine.DeviceSummary = _source.DeviceSummary;

        if (_sender.FailedCount > _sendFailuresSeen)
        {
            _sendFailuresSeen = (int)_sender.FailedCount;
            _engine.Note("UDP 发送失败，检查端口是否被占用");
        }

        _panel.UpdateState(_engine.SnapshotSlots(), _engine.SnapshotLog());
        _panel.Invalidate();

        _header.Sending = _engine.Sending;
        _header.TrackpadOk = _source.HasTouchpad;
        _header.StatusText = _header.TrackpadOk
            ? (_engine.Sending ? "正在发送" : "已暂停")
            : "读不到触控板（需精确式触控板）";
        _header.Endpoint = $"→  127.0.0.1:{_sender.Port}";
        _header.DeviceText = _engine.DeviceSummary;
        _header.FocusText = _controlError ?? (_guard.State switch
        {
            RecordLockState.Locked => "● 鼠标已锁定 · ESC 解除",
            RecordLockState.Unlocked => "● 鼠标已解除 · Alt+Enter 锁定",
            _ => string.Empty,
        });
        _header.FocusColor = (_controlError is not null
            || _guard.State == RecordLockState.Unlocked
            || (_guard.State != RecordLockState.Idle && _guard.Warning is not null))
            ? TouchPanel.Pal.Warn
            : TouchPanel.Pal.Ok;
        _header.Invalidate();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _guard.ExitRecording();
        if (_receiver is not null)
        {
            _receiver.CommandReceived -= OnControlCommand;
            _receiver.Dispose();
        }
        _timer.Stop();
        _timer.Dispose();
        _source.FrameReady -= OnFrameReady;
        _source.Dispose();
        _sender.Dispose();
        base.OnFormClosed(e);
    }

    /// <summary>顶部状态栏：标题、状态圆点与文案、设备名。</summary>
    private sealed class StatusHeader : Panel
    {
        public bool Sending { get; set; } = true;
        public bool TrackpadOk { get; set; }
        public string StatusText { get; set; } = "读不到触控板（需精确式触控板）";
        public string Endpoint { get; set; } = "→  127.0.0.1:9000";
        public string DeviceText { get; set; } = "未发现精确式触控板";
        public string FocusText { get; set; } = "";
        public Color FocusColor { get; set; } = TouchPanel.Pal.Ok;

        public StatusHeader()
        {
            DoubleBuffered = true;
            SetStyle(ControlStyles.ResizeRedraw, true);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float s = UiMetrics.ScaleOf(this);

            using (var title = new Font("Microsoft YaHei UI", 12.5f, FontStyle.Bold))
            using (var brush = new SolidBrush(TouchPanel.Pal.Text))
            {
                g.DrawString("触控板 → RPE", title, brush, 24 * s, 8 * s);
            }

            using (var sub = new Font("Microsoft YaHei UI", 8.5f))
            using (var dim = new SolidBrush(TouchPanel.Pal.Dim))
            {
                g.DrawString("把每根手指的位置 / 轨迹 / 区域 / 手势，变成 OSC 消息发给 RPE", sub, dim, 25 * s, 34 * s);
            }

            Color dot = !TrackpadOk
                ? TouchPanel.Pal.Warn
                : Sending ? TouchPanel.Pal.Ok : TouchPanel.Pal.Faint;

            using (var f = new Font("Microsoft YaHei UI", 9f))
            using (var brush = new SolidBrush(TouchPanel.Pal.Dim))
            {
                string status = $"{StatusText}  {Endpoint}";
                SizeF sz = g.MeasureString(status, f);
                float x = Math.Max(250 * s, Width - 24 * s - sz.Width);
                float y = 9 * s;
                g.DrawString(status, f, brush, x, y);

                using (var b = new SolidBrush(dot))
                {
                    g.FillEllipse(b, x - 15 * s, y + sz.Height / 2 - 4.5f * s, 9 * s, 9 * s);
                }

                using var small = new Font("Microsoft YaHei UI", 8f);
                SizeF ds = g.MeasureString(DeviceText, small);
                g.DrawString(DeviceText, small, brush, Math.Max(250 * s, Width - 24 * s - ds.Width), 31 * s);
            }

            if (!string.IsNullOrEmpty(FocusText))
            {
                using var focusFont = new Font("Microsoft YaHei UI", 8f);
                using var focusBrush = new SolidBrush(FocusColor);
                SizeF fs = g.MeasureString(FocusText, focusFont);
                g.DrawString(FocusText, focusFont, focusBrush,
                    Math.Max(455 * s, Width - 24 * s - fs.Width), 57 * s);
            }

            using var line = new Pen(Color.FromArgb(255, 255, 255).WithAlpha(0.07f));
            g.DrawLine(line, 0, Height - 1, Width, Height - 1);
        }
    }
}
