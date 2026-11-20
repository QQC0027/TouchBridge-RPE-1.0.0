namespace TouchBridge.Core;

/// <summary>
/// 一帧触点快照中的单个触点。字段与 Windows 精确式触控板（WPT）规范一一对应。
/// </summary>
/// <param name="DeviceId">hDevice 哈希，用于多设备隔离。</param>
/// <param name="ContactId">HID usage 0x0D/0x51，在同一接触期间保持不变。</param>
/// <param name="X">已按 LogicalMin/Max 归一化到 0..1，原点左上。</param>
/// <param name="Y">同上。</param>
/// <param name="Area">接触面积，0..1，作用于预览。</param>
/// <param name="Tip">true 表示手指仍接触触控板。</param>
/// <param name="ScanTime">usage 0x0D/0x56，单位 100µs，16 位会回绕。</param>
public readonly record struct TouchContact(
    uint DeviceId,
    int ContactId,
    float X,
    float Y,
    float Area,
    bool Tip,
    ushort ScanTime);

/// <summary>
/// 可复用的帧缓冲。采集线程写满后同步交给手势引擎消费，因此只保留一个实例，
/// 避免每帧（约 100Hz）产生垃圾。
/// </summary>
public sealed class ContactFrame
{
    private readonly TouchContact[] _items;
    private int _count;

    public ContactFrame(int capacity = 16)
    {
        _items = new TouchContact[Math.Max(capacity, 1)];
    }

    public int Count => _count;
    public ReadOnlySpan<TouchContact> Contacts => _items.AsSpan(0, _count);

    /// <summary>本次事件对应的高精度单调时间戳（Stopwatch 刻度）。</summary>
    public long TimestampTicks { get; set; }

    /// <summary>参与合并的设备数，用于诊断多触控板场景。</summary>
    public int DeviceCount { get; set; }

    public void Reset()
    {
        _count = 0;
    }

    public void Add(in TouchContact contact)
    {
        if (_count >= _items.Length) return;
        _items[_count++] = contact;
    }
}
