using System.Runtime.InteropServices;
using HidpData = TouchBridge.Native.NativeMethods.HidpData;

namespace TouchBridge.Native;

/// <summary>单个触点的原始解析结果（坐标已按逻辑范围归一化到 0..1，原点左上）。</summary>
internal struct HidContactRaw
{
    internal int ContactId;
    internal float X;
    internal float Y;
    internal float Width;
    internal float Height;
    internal bool Tip;
}

/// <summary>
/// 按 HID 报告描述符解析精确式触控板报告：设备首次出现时用 HidP_* 建立
/// 「数据索引 → 字段」映射表，之后逐帧读取 HidP_GetData 的结果。
/// 不硬编码任何报告布局，因此对并行 / 混合 / 单指增量上报都适用。
/// </summary>
internal sealed unsafe class HidReportParser
{
    private const ushort DigitizerPage = 0x0D;
    private const ushort GenericDesktopPage = 0x01;

    private const ushort UsageX = 0x30;
    private const ushort UsageY = 0x31;
    private const ushort UsageTipSwitch = 0x42;
    private const ushort UsageWidth = 0x48;
    private const ushort UsageHeight = 0x49;
    private const ushort UsageContactId = 0x51;
    private const ushort UsageContactCount = 0x54;
    private const ushort UsageScanTime = 0x56;

    private enum Field : byte
    {
        None, X, Y, Tip, Width, Height, ContactId, ScanTime, ContactCount,
    }

    private struct Target
    {
        internal Field Field;
        internal int Slot;   // -1 = report 级字段
        internal int Min;
        internal int Max;
    }

    private struct FieldDef
    {
        internal ushort Page;
        internal ushort Usage;
        internal ushort DataIndex;
        internal int Min;
        internal int Max;
        internal ushort Collection;
    }

    private readonly byte[] _preparsed;
    private readonly Dictionary<ushort, Target> _byDataIndex = new(64);
    private readonly int[] _contactId;
    private readonly float[] _x;
    private readonly float[] _y;
    private readonly float[] _w;
    private readonly float[] _h;
    private readonly bool[] _tip;
    private readonly bool[] _seen;
    private readonly bool[] _hasTip;
    private HidpData[] _data = new HidpData[64];

    private Target _scanTimeTarget;
    private bool _hasScanTime;
    private bool _hasContactCount;

    internal int InputReportLength { get; }
    internal byte ReportId { get; }
    internal int MaxContacts { get; }
    internal string DeviceName { get; }

    private HidReportParser(byte[] preparsed, int reportLength, byte reportId, int maxContacts, string deviceName)
    {
        _preparsed = preparsed;
        InputReportLength = reportLength;
        ReportId = reportId;
        MaxContacts = maxContacts;
        DeviceName = deviceName;
        _contactId = new int[maxContacts];
        _x = new float[maxContacts];
        _y = new float[maxContacts];
        _w = new float[maxContacts];
        _h = new float[maxContacts];
        _tip = new bool[maxContacts];
        _seen = new bool[maxContacts];
        _hasTip = new bool[maxContacts];
    }

    /// <summary>读取设备预解析数据并建表；不是精确式触控板时返回 null。</summary>
    internal static HidReportParser? TryCreate(nint device, string deviceName)
    {
        byte[]? preparsed = ReadPreparsedData(device);
        return preparsed is null ? null : Build(preparsed, deviceName);
    }

    /// <summary>用一段预解析数据直接建表（供离线自检使用）。</summary>
    internal static HidReportParser? TryCreate(byte[] preparsed, string deviceName) => Build(preparsed, deviceName);

    private static byte[]? ReadPreparsedData(nint device)
    {
        uint size = 0;
        _ = NativeMethods.GetRawInputDeviceInfo(device, NativeMethods.RidiPreparsedData, null, ref size);
        if (size == 0 || size > 64 * 1024) return null;
        // HID API 要求缓冲区按 DWORD 对齐
        var buffer = new byte[(int)((size + 3u) & ~3u)];
        fixed (byte* p = buffer)
        {
            uint got = NativeMethods.GetRawInputDeviceInfo(device, NativeMethods.RidiPreparsedData, p, ref size);
            if (got == 0 || got == uint.MaxValue) return null;
        }
        return buffer;
    }

    private static HidReportParser? Build(byte[] preparsed, string deviceName)
    {
        fixed (byte* p = preparsed)
        {
            if (NativeMethods.HidP_GetCaps(p, out NativeMethods.HidpCaps caps) != NativeMethods.HidpStatusSuccess)
                return null;
            // 只接受 Digitizer/Touch Pad 顶层集合，普通鼠标键盘直接忽略
            if (caps.UsagePage != DigitizerPage || caps.Usage != NativeMethods.TouchpadUsage)
                return null;
            if (caps.InputReportByteLength < 2 || caps.NumberInputValueCaps == 0) return null;

            ushort valueCount = caps.NumberInputValueCaps;
            ushort buttonCount = caps.NumberInputButtonCaps;
            var values = new NativeMethods.HidpValueCaps[valueCount];
            var buttons = new NativeMethods.HidpButtonCaps[Math.Max((int)buttonCount, 1)];

            fixed (NativeMethods.HidpValueCaps* vp = values)
            {
                if (NativeMethods.HidP_GetValueCaps(NativeMethods.HidpInput, vp, ref valueCount, p)
                    != NativeMethods.HidpStatusSuccess) return null;
            }
            if (buttonCount > 0)
            {
                fixed (NativeMethods.HidpButtonCaps* bp = buttons)
                {
                    if (NativeMethods.HidP_GetButtonCaps(NativeMethods.HidpInput, bp, ref buttonCount, p)
                        != NativeMethods.HidpStatusSuccess) return null;
                }
            }

            List<FieldDef> defs = CollectDefs(values, valueCount, buttons, buttonCount, out byte reportId);

            // 找出同时带 X 与 Y 的 Logical Collection，作为触点槽位
            var remap = new Dictionary<ushort, int>(8);
            var collections = new List<ushort>(8);
            foreach (FieldDef d in defs)
            {
                if (d.Collection != 0 && !collections.Contains(d.Collection)) collections.Add(d.Collection);
            }
            foreach (ushort c in collections)
            {
                bool hasX = false, hasY = false;
                foreach (FieldDef d in defs)
                {
                    if (d.Collection != c || d.Page != GenericDesktopPage) continue;
                    if (d.Usage == UsageX) hasX = true;
                    else if (d.Usage == UsageY) hasY = true;
                }
                if (hasX && hasY) remap[c] = remap.Count;
            }
            if (remap.Count == 0) return null;

            var parser = new HidReportParser(preparsed, caps.InputReportByteLength, reportId, remap.Count, deviceName);
            parser.MapFields(defs, remap);
            uint maxData = NativeMethods.HidP_MaxDataListLength(NativeMethods.HidpInput, p);
            if (maxData > 0 && maxData <= 4096) parser._data = new HidpData[maxData];
            return parser;
        }
    }

    private static List<FieldDef> CollectDefs(
        NativeMethods.HidpValueCaps[] values, int valueCount,
        NativeMethods.HidpButtonCaps[] buttons, int buttonCount,
        out byte reportId)
    {
        reportId = 0;
        var defs = new List<FieldDef>(valueCount + buttonCount);

        for (int i = 0; i < valueCount; i++)
        {
            ref NativeMethods.HidpValueCaps vc = ref values[i];
            if (reportId == 0 && vc.LinkCollection != 0) reportId = vc.ReportId;

            if (vc.IsRange == 0)
            {
                defs.Add(new FieldDef
                {
                    Page = vc.UsagePage,
                    Usage = vc.UsageMin,
                    DataIndex = vc.DataIndexMin,
                    Min = vc.LogicalMin,
                    Max = vc.LogicalMax,
                    Collection = vc.LinkCollection,
                });
                continue;
            }

            int n = vc.UsageMax >= vc.UsageMin ? Math.Min(vc.UsageMax - vc.UsageMin + 1, 64) : 0;
            for (int k = 0; k < n; k++)
            {
                defs.Add(new FieldDef
                {
                    Page = vc.UsagePage,
                    Usage = (ushort)(vc.UsageMin + k),
                    DataIndex = (ushort)(vc.DataIndexMin + k),
                    Min = vc.LogicalMin,
                    Max = vc.LogicalMax,
                    Collection = vc.LinkCollection,
                });
            }
        }

        for (int i = 0; i < buttonCount; i++)
        {
            ref NativeMethods.HidpButtonCaps bc = ref buttons[i];
            if (bc.UsagePage != DigitizerPage || bc.LinkCollection == 0) continue;
            if (bc.UsageMin > UsageTipSwitch || bc.UsageMax < UsageTipSwitch) continue;
            defs.Add(new FieldDef
            {
                Page = DigitizerPage,
                Usage = UsageTipSwitch,
                DataIndex = (ushort)(bc.DataIndexMin + (UsageTipSwitch - bc.UsageMin)),
                Min = 0,
                Max = 1,
                Collection = bc.LinkCollection,
            });
        }

        return defs;
    }

    private void MapFields(List<FieldDef> defs, Dictionary<ushort, int> remap)
    {
        foreach (FieldDef d in defs)
        {
            Field f = MapField(d.Page, d.Usage);
            if (f == Field.None) continue;

            if (d.Collection == 0)
            {
                if (d.Page != DigitizerPage) continue;
                if (f == Field.ScanTime)
                {
                    _scanTimeTarget = new Target { Field = f, Slot = -1, Min = d.Min, Max = d.Max };
                    _hasScanTime = true;
                }
                else if (f == Field.ContactCount)
                {
                    _hasContactCount = true;
                }
                continue;
            }

            if (!remap.TryGetValue(d.Collection, out int slot)) continue;
            _byDataIndex[d.DataIndex] = new Target { Field = f, Slot = slot, Min = d.Min, Max = d.Max };
            if (f == Field.Tip) _hasTip[slot] = true;
        }
    }

    private static Field MapField(ushort page, ushort usage)
    {
        if (page == GenericDesktopPage)
        {
            return usage switch { UsageX => Field.X, UsageY => Field.Y, _ => Field.None };
        }
        if (page != DigitizerPage) return Field.None;
        return usage switch
        {
            UsageTipSwitch => Field.Tip,
            UsageWidth => Field.Width,
            UsageHeight => Field.Height,
            UsageContactId => Field.ContactId,
            UsageScanTime => Field.ScanTime,
            UsageContactCount => Field.ContactCount,
            _ => Field.None,
        };
    }

    /// <summary>
    /// 解析一帧报告。返回有效触点数；scanTime 为设备扫描时间（100µs 单位，会回绕）；
    /// declared 为设备用 Contact Count 声明的总触点数，未上报时为 -1。
    /// </summary>
    internal int Parse(byte* report, uint length, Span<HidContactRaw> dest, out ushort scanTime, out int declared)
    {
        scanTime = 0;
        declared = -1;
        int n = Math.Min(MaxContacts, dest.Length);
        if (n == 0 || length < InputReportLength) return 0;

        for (int i = 0; i < n; i++)
        {
            _seen[i] = false;
            _x[i] = 0f;
            _y[i] = 0f;
            _w[i] = 0.5f;
            _h[i] = 0.5f;
            _tip[i] = false;
            _contactId[i] = i;
        }

        uint count = (uint)_data.Length;
        fixed (byte* p = _preparsed)
        fixed (HidpData* dp = _data)
        {
            if (NativeMethods.HidP_GetData(NativeMethods.HidpInput, dp, ref count, p, report, length)
                != NativeMethods.HidpStatusSuccess)
                return 0;

            for (uint i = 0; i < count; i++)
            {
                HidpData d = dp[i];
                if (!_byDataIndex.TryGetValue(d.DataIndex, out Target t)) continue;
                int slot = t.Slot;
                switch (t.Field)
                {
                    case Field.X:
                        _x[slot] = Norm((int)d.RawValue, t, 0f);
                        _seen[slot] = true;
                        break;
                    case Field.Y:
                        _y[slot] = Norm((int)d.RawValue, t, 0f);
                        _seen[slot] = true;
                        break;
                    case Field.Width:
                        _w[slot] = Norm((int)d.RawValue, t, 0.5f);
                        break;
                    case Field.Height:
                        _h[slot] = Norm((int)d.RawValue, t, 0.5f);
                        break;
                    case Field.ContactId:
                        _contactId[slot] = (int)d.RawValue;
                        break;
                    case Field.Tip:
                        _tip[slot] = d.On != 0 || d.RawValue != 0;
                        break;
                    case Field.ScanTime:
                        scanTime = (ushort)d.RawValue;
                        break;
                    case Field.ContactCount:
                        declared = (int)d.RawValue;
                        break;
                }
            }
        }

        int written = 0;
        for (int s = 0; s < n; s++)
        {
            // 有 Tip Switch 时以它为准；缺失时退化为「设备声明的前 N 个槽位有效」，
            // 避免并行上报里未使用的槽位被当成真实手指。
            bool present;
            if (_hasTip[s]) present = _tip[s];
            else present = _seen[s] && (declared < 0 || s < declared);
            if (!present) continue;

            dest[written++] = new HidContactRaw
            {
                ContactId = _contactId[s],
                X = _x[s],
                Y = _y[s],
                Width = _w[s],
                Height = _h[s],
                Tip = true,
            };
        }
        return written;
    }

    /// <summary>描述符摘要，用于 --diagnose 输出与排障。</summary>
    internal string Describe()
    {
        int tipSlots = 0;
        foreach (bool hasTip in _hasTip) if (hasTip) tipSlots++;
        return $"contacts={MaxContacts} tipSlots={tipSlots} scanTime={(_hasScanTime ? 1 : 0)} " +
               $"contactCount={(_hasContactCount ? 1 : 0)} reportLen={InputReportLength} reportId={ReportId}";
    }

    /// <summary>上一帧的 Report ID / 扫描时间是否可用于时间戳回绕计算。</summary>
    internal bool SupportsScanTime => _hasScanTime;
    internal bool SupportsContactCount => _hasContactCount;

    internal ushort PeekScanTime(byte* report, uint length)
    {
        if (!_hasScanTime || length < InputReportLength) return 0;
        // 扫描时间总是 16 位无符号；用 HidP_GetUsageValue 单独读取可避免整帧解析开销
        uint value = 0;
        fixed (byte* p = _preparsed)
        {
            if (NativeMethods.HidP_GetUsageValue(NativeMethods.HidpInput, DigitizerPage, 0, UsageScanTime,
                    &value, p, report, length) != NativeMethods.HidpStatusSuccess)
                return 0;
        }
        return (ushort)value;
    }

    private static float Norm(int raw, in Target t, float fallback)
    {
        if (t.Max <= t.Min) return fallback;
        float v = (raw - t.Min) / (float)(t.Max - t.Min);
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
