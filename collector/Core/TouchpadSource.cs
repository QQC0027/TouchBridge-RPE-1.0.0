using System.Runtime.InteropServices;
using TouchBridge.Native;

namespace TouchBridge.Core;

/// <summary>
/// 通过 Raw Input 读取精确式触控板（Digitizer / Touch Pad，usage 0x0D/0x05）的原始报告。
/// 每台设备独立维护解析上下文与触点缓存，支持热插拔；报告内容变化时抛出 <see cref="FrameReady"/>。
/// 所有回调都在创建该对象的线程（界面线程）上发生。
/// </summary>
public sealed unsafe class TouchpadSource : IDisposable
{
    private sealed class DeviceContext
    {
        internal required HidReportParser Parser { get; init; }
        internal required string Name { get; init; }
        internal Dictionary<int, TouchContact> Contacts { get; } = new(8);
    }

    private sealed class MessageWindow : NativeWindow
    {
        private readonly TouchpadSource _owner;

        internal MessageWindow(TouchpadSource owner) => _owner = owner;

        protected override void WndProc(ref Message m)
        {
            try
            {
                switch (m.Msg)
                {
                    case NativeMethods.WmInput:
                        _owner.ProcessRawInput(m.LParam);
                        break;
                    case NativeMethods.WmInputDeviceChange:
                        _owner.ProcessDeviceChange(m.WParam, m.LParam);
                        break;
                }
            }
            catch (Exception ex)
            {
                _owner.LastError = ex.Message;
            }
            // 必须交给 DefWindowProc，由它负责释放 Raw Input 缓冲区
            base.WndProc(ref m);
        }
    }

    private readonly MessageWindow _window;
    private readonly Dictionary<uint, DeviceContext> _devices = new();
    private readonly ContactFrame _frame = new();
    private readonly byte[] _raw = new byte[16 * 1024];
    private readonly HidContactRaw[] _scratch = new HidContactRaw[32];
    private bool _registered;
    private bool _disposed;

    /// <summary>一帧合并后的触点快照（复用实例，仅在下一次事件前有效）。</summary>
    public event Action<ContactFrame>? FrameReady;

    public bool HasTouchpad => _devices.Count > 0;
    public int DeviceCount => _devices.Count;
    public string DeviceSummary { get; private set; } = "未发现精确式触控板";
    public string Diagnostics { get; private set; } = "no device";
    public string? LastError { get; set; }

    public TouchpadSource()
    {
        _window = new MessageWindow(this);
        _window.CreateHandle(new CreateParams
        {
            Caption = "TouchBridgeRawInputWindow",
            Style = 0,
            ExStyle = 0,
        });
        Register(_window.Handle);
    }

    private void Register(nint hwnd)
    {
        var device = new NativeMethods.RawInputDevice
        {
            UsagePage = NativeMethods.DigitizerPage,
            Usage = NativeMethods.TouchpadUsage,
            Flags = NativeMethods.RidevInputSink | NativeMethods.RidevDevNotify,
            Target = hwnd,
        };
        _registered = NativeMethods.RegisterRawInputDevices(device, 1, (uint)Marshal.SizeOf<NativeMethods.RawInputDevice>());
        if (!_registered)
        {
            LastError = $"Raw Input 注册失败（错误码 {Marshal.GetLastWin32Error()}）";
            // 注册失败时补一次无 INPUTSINK 的注册作为兜底（仅前台窗口能收到）
            device.Flags = NativeMethods.RidevDevNotify;
            NativeMethods.RegisterRawInputDevices(device, 1, (uint)Marshal.SizeOf<NativeMethods.RawInputDevice>());
        }
    }

    private void Unregister(nint hwnd)
    {
        var device = new NativeMethods.RawInputDevice
        {
            UsagePage = NativeMethods.DigitizerPage,
            Usage = NativeMethods.TouchpadUsage,
            Flags = NativeMethods.RidevRemove,
            Target = hwnd,
        };
        NativeMethods.RegisterRawInputDevices(device, 1, (uint)Marshal.SizeOf<NativeMethods.RawInputDevice>());
        _registered = false;
    }

    private void ProcessDeviceChange(nint wParam, nint lParam)
    {
        if ((int)wParam == NativeMethods.DeviceArrival)
        {
            TryAddDevice(lParam);
        }
        else if ((int)wParam == NativeMethods.DeviceRemoval)
        {
            if (_devices.Remove(ToDeviceId(lParam)))
            {
                UpdateSummary();
                EmitFrame();
            }
        }
    }

    private static uint ToDeviceId(nint handle) => unchecked((uint)handle.GetHashCode());

    private void TryAddDevice(nint handle)
    {
        uint id = ToDeviceId(handle);
        if (_devices.ContainsKey(id)) return;

        string name = ReadDeviceName(handle);
        HidReportParser? parser = HidReportParser.TryCreate(handle, name);
        if (parser is null) return;

        _devices[id] = new DeviceContext { Parser = parser, Name = name };
        Diagnostics = $"{name} :: {parser.Describe()}";
        UpdateSummary();
    }

    private static string ReadDeviceName(nint handle)
    {
        uint size = 0;
        _ = NativeMethods.GetRawInputDeviceInfo(handle, NativeMethods.RidiDeviceInfo, null, ref size);
        if (size == 0 || size > 4096) return "精确式触控板";
        var buffer = new byte[size * 2 + 2];
        fixed (byte* p = buffer)
        {
            uint got = NativeMethods.GetRawInputDeviceInfo(handle, NativeMethods.RidiDeviceInfo, p, ref size);
            if (got == 0 || got == uint.MaxValue) return "精确式触控板";
        }
        string raw = System.Text.Encoding.Unicode.GetString(buffer).TrimEnd('\0');
        string friendly = raw.Replace('#', '\\');

        // 设备名是 HID 接口路径；若取到的字节不是合法 UTF-16 会变成替换字符，此时退回通用名称
        bool valid = friendly.Length > 0 && friendly.Length <= 512;
        foreach (char ch in friendly)
        {
            if (ch == '\uFFFD' || (ch < 0x20 && ch != '\t'))
            {
                valid = false;
                break;
            }
        }
        return valid ? friendly : "精确式触控板";
    }

    private void UpdateSummary()
    {
        if (_devices.Count == 0)
        {
            DeviceSummary = "未发现精确式触控板";
            return;
        }
        // 状态栏只显示简洁的可用性文案，完整设备路径放进 Diagnostics 便于排障
        DeviceSummary = _devices.Count == 1 ? "1 台精确式触控板已连接" : $"{_devices.Count} 台精确式触控板已连接";
    }

    private void ProcessRawInput(nint lParam)
    {
        uint size = (uint)_raw.Length;
        uint headerSize = (uint)Marshal.SizeOf<NativeMethods.RawInputHeader>();
        fixed (byte* p = _raw)
        {
            uint got = NativeMethods.GetRawInputData(lParam, NativeMethods.RidInput, p, ref size, headerSize);
            if (got == 0 || got == uint.MaxValue) return;

            var header = (NativeMethods.RawInputHeader*)p;
            if (header->Type != NativeMethods.RimTypeHid) return;

            uint id = ToDeviceId(header->Device);
            if (!_devices.TryGetValue(id, out DeviceContext? context))
            {
                // 首帧才发现的设备（热插拔通知可能丢失）
                TryAddDevice(header->Device);
                if (!_devices.TryGetValue(id, out context)) return;
            }

            ProcessReports(context, id, p, size, headerSize);
        }

        EmitFrame();
    }

    private void ProcessReports(DeviceContext context, uint deviceId, byte* raw, uint totalSize, uint headerSize)
    {
        if (totalSize < headerSize + 8) return;
        byte* hid = raw + headerSize;
        uint reportSize = *(uint*)hid;
        uint reportCount = *(uint*)(hid + 4);
        if (reportSize == 0 || reportCount == 0 || reportSize > 8192 || reportCount > 64) return;

        byte* data = hid + 8;
        uint available = totalSize - headerSize - 8;
        if (reportSize * reportCount > available) return;

        bool anyContact = false;
        for (uint i = 0; i < reportCount; i++)
        {
            byte* report = data + i * reportSize;
            int n = context.Parser.Parse(report, reportSize, _scratch, out ushort ts, out _);
            if (n > 0) anyContact = true;

            for (int k = 0; k < n; k++)
            {
                HidContactRaw rawContact = _scratch[k];
                float area = rawContact.Width > 0 && rawContact.Height > 0
                    ? MathF.Min(1f, MathF.Sqrt(rawContact.Width * rawContact.Height) * 2f)
                    : 0.5f;
                context.Contacts[rawContact.ContactId] = new TouchContact(
                    deviceId,
                    rawContact.ContactId,
                    rawContact.X,
                    rawContact.Y,
                    area,
                    rawContact.Tip,
                    ts);
            }
        }

        if (!anyContact)
        {
            // 设备已无触点：清空该设备缓存，避免漏抬手导致槽位卡死
            context.Contacts.Clear();
        }
        else
        {
            // Tip Switch 为 false 的条目立即移除
            List<int>? stale = null;
            foreach (KeyValuePair<int, TouchContact> kv in context.Contacts)
            {
                if (!kv.Value.Tip) (stale ??= new List<int>(4)).Add(kv.Key);
            }
            if (stale is not null)
            {
                foreach (int key in stale) context.Contacts.Remove(key);
            }
        }
    }

    private void EmitFrame()
    {
        _frame.Reset();
        _frame.TimestampTicks = System.Diagnostics.Stopwatch.GetTimestamp();
        _frame.DeviceCount = _devices.Count;
        foreach (DeviceContext ctx in _devices.Values)
        {
            foreach (TouchContact c in ctx.Contacts.Values)
            {
                if (c.Tip) _frame.Add(c);
            }
        }
        FrameReady?.Invoke(_frame);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_window.Handle != 0)
        {
            Unregister(_window.Handle);
            _window.DestroyHandle();
        }
        _devices.Clear();
    }
}
