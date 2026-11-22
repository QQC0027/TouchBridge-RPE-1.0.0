using System.Text;
using System.Text.Json;
using TouchBridge.Core;

namespace TouchBridge;

/// <summary>
/// 行分隔 JSON 管道：标准输出发事件，标准输入收命令。
/// 这是替代 UDP/OSC 的唯一通信通道，因此进程不监听任何端口。
/// </summary>
internal sealed class PipeChannel : ITouchSink, IDisposable
{
    private readonly object _sync = new();
    private readonly Stream _stdout;
    private readonly MemoryStream _buffer = new(1024);
    private readonly Utf8JsonWriter _json;

    public PipeChannel()
    {
        // WinExe 没有控制台，但 stdout 被父进程重定向后依然可用
        _stdout = Console.OpenStandardOutput();
        _json = new Utf8JsonWriter(_buffer, new JsonWriterOptions { SkipValidation = true });
    }

    public long SentCount { get; private set; }
    public long FailedCount { get; private set; }

    /// <summary>写一条事件：<c>{"type": "...", ...}</c>。失败时返回 false，绝不抛异常。</summary>
    public bool Event(string type, Action<Utf8JsonWriter>? body = null)
    {
        lock (_sync)
        {
            try
            {
                _buffer.SetLength(0);
                _json.Reset(_buffer);
                _json.WriteStartObject();
                _json.WriteString("type", type);
                body?.Invoke(_json);
                _json.WriteEndObject();
                _json.Flush();

                _stdout.Write(_buffer.GetBuffer(), 0, (int)_buffer.Length);
                _stdout.WriteByte((byte)'\n');
                _stdout.Flush();
                SentCount++;
                return true;
            }
            catch (Exception)
            {
                FailedCount++;
                return false;
            }
        }
    }

    public bool Send(string address, ReadOnlySpan<OscArg> args)
    {
        OscArg[] copy = args.ToArray(); // 闭包里不能捕获 Span
        return Event("gesture", writer =>
        {
            writer.WriteString("address", address);
            writer.WriteStartArray("args");
            foreach (OscArg arg in copy)
            {
                if (arg.Kind == OscArgKind.Int) writer.WriteNumberValue(arg.IntValue);
                else writer.WriteNumberValue(arg.FloatValue);
            }
            writer.WriteEndArray();
        });
    }

    /// <summary>后台读标准输入；stdin 关闭（主程序退出）时回调 <c>onClosed</c>。</summary>
    public void StartCommandLoop(Action<JsonElement> onCommand, Action onClosed)
    {
        var thread = new Thread(() =>
        {
            try
            {
                using var reader = new StreamReader(Console.OpenStandardInput(),
                    new UTF8Encoding(false), detectEncodingFromByteOrderMarks: false);
                string? line;
                while ((line = reader.ReadLine()) is not null)
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    try
                    {
                        using JsonDocument document = JsonDocument.Parse(line);
                        onCommand(document.RootElement.Clone());
                    }
                    catch (JsonException)
                    {
                        // 坏行直接丢弃：协议错误不应该打断采集
                    }
                }
            }
            catch (IOException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
            finally
            {
                onClosed();
            }
        })
        {
            IsBackground = true,
            Name = "pipe-in",
        };
        thread.Start();
    }

    public void Dispose()
    {
        lock (_sync)
        {
            try
            {
                _json.Dispose();
                _stdout.Dispose();
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }
}