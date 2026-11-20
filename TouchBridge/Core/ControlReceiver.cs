using System.Net;
using System.Net.Sockets;
using System.Text;

namespace TouchBridge.Core;

/// <summary>
/// 监听后端发来的录制控制指令（UDP 回环 127.0.0.1:9001）。
/// 协议为单行文本："TBCTRL REC_START\n" 或 "TBCTRL REC_STOP\n"。
/// 事件在后台线程触发，订阅者必须自行切回 UI 线程再操作 Win32 / 控件。
/// </summary>
public sealed class ControlReceiver : IDisposable
{
    public const int DefaultPort = 9001;
    private const string Prefix = "TBCTRL";

    private readonly UdpClient _client;
    private readonly Thread _thread;
    private volatile bool _stopping;
    private bool _disposed;

    public int Port { get; }
    public string? LastError { get; private set; }

    /// <summary>收到合法指令时触发，参数为 "REC_START" / "REC_STOP"。</summary>
    public event Action<string>? CommandReceived;

    public ControlReceiver(int port = DefaultPort)
    {
        Port = port;
        _client = new UdpClient(new IPEndPoint(IPAddress.Loopback, port));
        _thread = new Thread(Loop)
        {
            IsBackground = true,
            Name = "TouchBridge.ControlReceiver",
        };
        _thread.Start();
    }

    private void Loop()
    {
        while (!_stopping)
        {
            try
            {
                var remote = new IPEndPoint(IPAddress.Any, 0);
                byte[] data = _client.Receive(ref remote);
                string text = Encoding.UTF8.GetString(data).Trim();
                if (!text.StartsWith(Prefix + " ", StringComparison.OrdinalIgnoreCase)) continue;

                string command = text[Prefix.Length..].Trim().ToUpperInvariant();
                if (command is "REC_START" or "REC_STOP")
                {
                    CommandReceived?.Invoke(command);
                }
            }
            catch (SocketException) when (_stopping)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _stopping = true;
        try
        {
            _client.Close();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}
