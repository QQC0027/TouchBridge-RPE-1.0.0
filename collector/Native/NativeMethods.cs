using System.Runtime.InteropServices;

namespace TouchBridge.Native;

internal static unsafe class NativeMethods
{
    internal const int WmInput = 0x00FF;
    internal const int WmInputDeviceChange = 0x00FE;
    internal const int DeviceArrival = 1;
    internal const int DeviceRemoval = 2;
    internal const uint RidevRemove = 0x00000001;
    internal const uint RidevInputSink = 0x00000100;
    internal const uint RidevDevNotify = 0x00002000;
    internal const uint RidInput = 0x10000003;
    internal const uint RidiPreparsedData = 0x20000005;
    internal const uint RidiDeviceInfo = 0x2000000B;
    internal const uint RimTypeHid = 2;
    internal const ushort DigitizerPage = 0x0D;
    internal const ushort TouchpadUsage = 0x05;
    internal const int HidpInput = 0;
    internal const int HidpStatusSuccess = 0x00110000;
    internal const int WmDpiChanged = 0x02E0;

    // 录制专注模式：全局热键与系统设置广播
    internal const int WmHotkey = 0x0312;
    internal const uint WmSettingChange = 0x001A;
    internal const uint SmtoAbortifhung = 0x0002;
    internal const uint ModAlt = 0x0001;
    internal const uint VkEscape = 0x1B;
    internal const uint VkReturn = 0x0D;
    internal static readonly nint HwndBroadcast = (nint)0xFFFF;

    [DllImport("user32.dll")]
    internal static extern int GetDpiForWindow(nint hwnd);

    [StructLayout(LayoutKind.Sequential)]
    internal struct RawInputDevice
    {
        internal ushort UsagePage;
        internal ushort Usage;
        internal uint Flags;
        internal nint Target;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct RawInputHeader
    {
        internal uint Type;
        internal uint Size;
        internal nint Device;
        internal nuint WParam;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct RawHidHeader
    {
        internal uint ReportSize;
        internal uint ReportCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct RawInputDeviceListEntry
    {
        internal nint Device;
        internal uint Type;
    }

    [StructLayout(LayoutKind.Explicit, Size = 32)]
    internal struct RawInputDeviceInfo
    {
        [FieldOffset(0)] internal uint Size;
        [FieldOffset(4)] internal uint Type;
        [FieldOffset(20)] internal ushort UsagePage;
        [FieldOffset(22)] internal ushort Usage;
    }

    [StructLayout(LayoutKind.Explicit, Size = 64)]
    internal struct HidpCaps
    {
        [FieldOffset(0)] internal ushort Usage;
        [FieldOffset(2)] internal ushort UsagePage;
        [FieldOffset(4)] internal ushort InputReportByteLength;
        [FieldOffset(46)] internal ushort NumberInputButtonCaps;
        [FieldOffset(48)] internal ushort NumberInputValueCaps;
    }

    // 只映射用得到的字段，保留 Windows SDK 的结构体大小与联合体偏移。
    [StructLayout(LayoutKind.Explicit, Size = 72)]
    internal struct HidpValueCaps
    {
        [FieldOffset(0)] internal ushort UsagePage;
        [FieldOffset(2)] internal byte ReportId;
        [FieldOffset(3)] internal byte IsAlias;
        [FieldOffset(6)] internal ushort LinkCollection;
        [FieldOffset(12)] internal byte IsRange;
        [FieldOffset(16)] internal byte HasNull;
        [FieldOffset(18)] internal ushort BitSize;
        [FieldOffset(40)] internal int LogicalMin;
        [FieldOffset(44)] internal int LogicalMax;
        [FieldOffset(56)] internal ushort UsageMin;
        [FieldOffset(58)] internal ushort UsageMax;
        [FieldOffset(68)] internal ushort DataIndexMin;
    }

    [StructLayout(LayoutKind.Explicit, Size = 72)]
    internal struct HidpButtonCaps
    {
        [FieldOffset(0)] internal ushort UsagePage;
        [FieldOffset(2)] internal byte ReportId;
        [FieldOffset(3)] internal byte IsAlias;
        [FieldOffset(6)] internal ushort LinkCollection;
        [FieldOffset(12)] internal byte IsRange;
        [FieldOffset(56)] internal ushort UsageMin;
        [FieldOffset(58)] internal ushort UsageMax;
        [FieldOffset(68)] internal ushort DataIndexMin;
    }

    [StructLayout(LayoutKind.Explicit, Size = 8)]
    internal struct HidpData
    {
        [FieldOffset(0)] internal ushort DataIndex;
        [FieldOffset(4)] internal uint RawValue;
        [FieldOffset(4)] internal byte On;
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool RegisterRawInputDevices(in RawInputDevice device, uint count, uint size);

    [DllImport("user32.dll", SetLastError = true)]
    internal static extern uint GetRawInputData(nint input, uint command, void* buffer, ref uint size, uint headerSize);

    [DllImport("user32.dll", EntryPoint = "GetRawInputDeviceInfoW", ExactSpelling = true, SetLastError = true)]
    internal static extern uint GetRawInputDeviceInfo(nint device, uint command, void* buffer, ref uint size);

    [DllImport("user32.dll", SetLastError = true)]
    internal static extern uint GetRawInputDeviceList(RawInputDeviceListEntry* devices, ref uint count, uint size);

    [DllImport("hid.dll")]
    internal static extern int HidP_GetCaps(byte* preparsed, out HidpCaps caps);

    [DllImport("hid.dll")]
    internal static extern int HidP_GetValueCaps(int reportType, HidpValueCaps* caps, ref ushort length, byte* preparsed);

    [DllImport("hid.dll")]
    internal static extern int HidP_GetButtonCaps(int reportType, HidpButtonCaps* caps, ref ushort length, byte* preparsed);

    [DllImport("hid.dll")]
    internal static extern uint HidP_MaxDataListLength(int reportType, byte* preparsed);

    [DllImport("hid.dll")]
    internal static extern int HidP_GetData(int reportType, HidpData* data, ref uint length, byte* preparsed, byte* report, uint reportLength);

    [DllImport("hid.dll")]
    internal static extern int HidP_GetUsageValue(int reportType, ushort usagePage, ushort linkCollection, ushort usage,
        uint* value, byte* preparsed, byte* report, uint reportLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool AttachConsole(uint processId);

    [DllImport("kernel32.dll")]
    internal static extern nint GetStdHandle(int standardHandle);

    [StructLayout(LayoutKind.Sequential)]
    internal struct Point
    {
        internal int X;
        internal int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct Rect
    {
        internal int Left;
        internal int Top;
        internal int Right;
        internal int Bottom;
    }

    [DllImport("user32.dll")]
    internal static extern int ShowCursor([MarshalAs(UnmanagedType.Bool)] bool bShow);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetCursorPos(out Point lpPoint);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ClipCursor(Rect* lpRect);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool RegisterHotKey(nint hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool UnregisterHotKey(nint hWnd, int id);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    internal static extern nint SendMessageTimeout(nint hWnd, uint Msg, nint wParam,
        [MarshalAs(UnmanagedType.LPWStr)] string lParam, uint fuFlags, uint uTimeout, out nint lpdwResult);
}
