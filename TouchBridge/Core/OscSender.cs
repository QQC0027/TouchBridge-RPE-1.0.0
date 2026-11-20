using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace TouchBridge.Core;

public enum OscArgKind : byte
{
    Int,
    Float,
}

/// <summary>一条 OSC 参数；用结构体避免每帧产生装箱。</summary>
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
/// 与旧版 Swift OSCSender 完全一致的 UDP 发送器：地址与类型标签各自补零到 4 字节对齐，
/// 参数按大端写入 int32 / float32，复用同一个 socket 发往 127.0.0.1。
/// </summary>
public sealed class OscSender : IDisposable
{
    public const int DefaultPort = 9000;

    private readonly object _sync = new();
    private readonly byte[] _buffer = new byte[256];
    private Socket? _socket;
    private IPEndPoint _target;

    public int Port { get; private set; } = DefaultPort;
    public string HostName { get; } = "127.0.0.1";
    public long SentCount { get; private set; }
    public long FailedCount { get; private set; }
    public bool Ready => _socket is not null;

    public OscSender(int port = DefaultPort)
    {
        _target = new IPEndPoint(IPAddress.Loopback, port);
        Open(port);
    }

    /// <summary>切换端口；失败时保留原 socket 并返回 false。</summary>
    public bool Open(int port)
    {
        if (port <= 0 || port > 65535) return false;
        lock (_sync)
        {
            try
            {
                var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                socket.SendTimeout = 500;
                _socket?.Dispose();
                _socket = socket;
                _target = new IPEndPoint(IPAddress.Loopback, port);
                Port = port;
                return true;
            }
            catch (SocketException)
            {
                FailedCount++;
                return false;
            }
        }
    }

    public bool Send(string address, ReadOnlySpan<OscArg> args)
    {
        lock (_sync)
        {
            Socket? socket = _socket;
            if (socket is null) return false;

            try
            {
                int length = BuildPacket(address, args, _buffer);
                if (length == 0) { FailedCount++; return false; }
                socket.SendTo(_buffer.AsSpan(0, length), _target);
                SentCount++;
                return true;
            }
            catch (SocketException)
            {
                FailedCount++;
                return false;
            }
            catch (ObjectDisposedException)
            {
                return false;
            }
        }
    }

    /// <summary>把 OSC 报文写入目标缓冲，返回字节数；空间不足时返回 0。</summary>
    public static int BuildPacket(string address, ReadOnlySpan<OscArg> args, Span<byte> destination)
    {
        int pos = 0;
        if (!WritePaddedString(destination, ref pos, address)) return 0;

        int tagLength = args.Length + 1;
        if (pos + Align4(tagLength + 1) > destination.Length) return 0;
        destination[pos++] = (byte)',';
        foreach (OscArg a in args)
        {
            destination[pos++] = a.Kind == OscArgKind.Int ? (byte)'i' : (byte)'f';
        }
        destination[pos++] = 0;
        while ((pos & 3) != 0) destination[pos++] = 0;

        foreach (OscArg a in args)
        {
            if (pos + 4 > destination.Length) return 0;
            if (a.Kind == OscArgKind.Int)
            {
                BinaryPrimitives.WriteInt32BigEndian(destination.Slice(pos), a.IntValue);
            }
            else
            {
                BinaryPrimitives.WriteInt32BigEndian(destination.Slice(pos), BitConverter.SingleToInt32Bits(a.FloatValue));
            }
            pos += 4;
        }
        return pos;
    }

    private static bool WritePaddedString(Span<byte> destination, ref int pos, string value)
    {
        int byteCount = System.Text.Encoding.UTF8.GetByteCount(value) + 1;
        int padded = Align4(byteCount);
        if (pos + padded > destination.Length) return false;
        System.Text.Encoding.UTF8.GetBytes(value, destination.Slice(pos));
        pos += System.Text.Encoding.UTF8.GetByteCount(value);
        destination[pos++] = 0;
        while ((pos & 3) != 0) destination[pos++] = 0;
        return true;
    }

    private static int Align4(int value) => (value + 3) & ~3;

    /// <summary>发一轮示例报文，用于 --osctest 自检。</summary>
    public int SendSample()
    {
        int before = (int)SentCount;
        Send("/count", stackalloc[] { OscArg.Int(1) });
        Send("/down", stackalloc[] { OscArg.Int(1), OscArg.Float(0.32f), OscArg.Float(0.28f), OscArg.Float(0.5f), OscArg.Int(0) });
        Send("/move", stackalloc[] { OscArg.Int(1), OscArg.Float(0.38f), OscArg.Float(0.34f), OscArg.Float(0.5f) });
        Send("/drag", stackalloc[] { OscArg.Int(1), OscArg.Float(0.55f), OscArg.Float(0.5f) });
        Send("/hold", stackalloc[] { OscArg.Int(1), OscArg.Float(0.72f), OscArg.Float(0.66f) });
        Send("/tap", stackalloc[] { OscArg.Int(2), OscArg.Float(0.5f), OscArg.Float(0.5f), OscArg.Float(86f) });
        Send("/up", stackalloc[] { OscArg.Int(2), OscArg.Float(143f), OscArg.Float(0.031f) });
        return (int)SentCount - before;
    }

    public void Dispose()
    {
        lock (_sync)
        {
            _socket?.Dispose();
            _socket = null;
        }
    }
}
