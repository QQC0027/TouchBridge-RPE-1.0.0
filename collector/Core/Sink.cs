namespace TouchBridge.Core;

public enum OscArgKind : byte
{
    Int,
    Float,
}

/// <summary>一个手势参数；用结构体避免每帧产生装箱。</summary>
public readonly struct OscArg
{
    public readonly OscArgKind Kind;
    public readonly int IntValue;
    public readonly float FloatValue;

    private OscArg(OscArgKind kind, int i, float f)
    {
        Kind = kind;
        IntValue = i;
        FloatValue = f;
    }

    public static OscArg Int(int value) => new(OscArgKind.Int, value, 0f);
    public static OscArg Float(float value) => new(OscArgKind.Float, 0, value);
}

/// <summary>
/// 手势输出通道。旧版是 UDP/OSC（127.0.0.1:9000），一体化版本改为标准输出管道，
/// 由主程序直接读取，因此不再监听任何端口。
/// </summary>
public interface ITouchSink
{
    /// <summary>发送一条手势消息；地址沿用原 OSC 语义（/down、/move、/tap…）。</summary>
    bool Send(string address, ReadOnlySpan<OscArg> args);
}