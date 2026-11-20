using TouchBridge.Core;
using TouchBridge.Native;
using TouchBridge.UI;

namespace TouchBridge;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // 上次异常退出若遗留触控板手势备份，先恢复，避免系统设置被永久改写
        RecordingGuard.EmergencyRestore();

        if (args.Any(a => a.Equals("--osctest", StringComparison.OrdinalIgnoreCase)))
        {
            RunOscTest(args);
            return;
        }

        if (args.Any(a => a.Equals("--diagnose", StringComparison.OrdinalIgnoreCase)))
        {
            RunDiagnose();
            return;
        }

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        Application.ThreadException += (_, e) => ShowFatal(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => ShowFatal(e.ExceptionObject as Exception);

        Application.Run(new MainForm());
    }

    /// <summary>无界面自检：发一轮示例 OSC 报文并打印发送计数，用于验证到后端的链路。</summary>
    private static void RunOscTest(string[] args)
    {
        int port = OscSender.DefaultPort;
        for (int i = 0; i < args.Length - 1; i++)
        {
            if (args[i] is "--port" or "-p" && int.TryParse(args[i + 1], out int parsed) && parsed > 0 && parsed <= 65535)
            {
                port = parsed;
            }
        }

        using var sender = new OscSender(port);
        if (!sender.Ready)
        {
            WriteLines($"failed to open UDP socket for 127.0.0.1:{port}");
            return;
        }

        int sent = sender.SendSample();
        // 控制台只用 ASCII，避免不同代码页下的乱码
        WriteLines(
            $"target: 127.0.0.1:{port}",
            $"sent: {sent}",
            $"failed: {sender.FailedCount}",
            "hint: open the web UI and check the OSC monitor card.");
    }

    /// <summary>诊断模式：监听 1.5 秒，打印设备描述符摘要与前若干帧的触点。</summary>
    private static void RunDiagnose()
    {
        var sb = new System.Text.StringBuilder();
        using var source = new TouchpadSource();
        sb.AppendLine($"devices={source.DeviceCount} diagnostics={source.Diagnostics}");
        if (source.LastError is not null) sb.AppendLine($"error={source.LastError}");

        int frames = 0;
        source.FrameReady += frame =>
        {
            frames++;
            if (frames > 8) return;
            sb.Append($"frame{frames} count={frame.Count} ");
            foreach (TouchBridge.Core.TouchContact c in frame.Contacts)
            {
                sb.Append($"[id={c.ContactId} x={c.X:0.00} y={c.Y:0.00} tip={(c.Tip ? 1 : 0)}] ");
            }
            sb.AppendLine();
        };

        var timer = new System.Windows.Forms.Timer { Interval = 3000 };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            Application.Exit();
        };
        timer.Start();
        Application.Run();
        sb.AppendLine($"totalFrames={frames}");
        sb.AppendLine($"summary={source.DeviceSummary}");
        sb.AppendLine($"diag={source.Diagnostics}");
        if (source.LastError is not null) sb.AppendLine($"lastError={source.LastError}");

        // 控制台代码页可能不是 UTF-8，另存一份到临时目录便于核对中文设备名
        try
        {
            string path = Path.Combine(Path.GetTempPath(), "touchbridge-diagnose.txt");
            File.WriteAllText(path, sb.ToString(), System.Text.Encoding.UTF8);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
        WriteLines(sb.ToString().Split('\n'));
    }

    /// <summary>
    /// WinExe 没有自带控制台：先尝试附加父进程控制台，再直接写 CONOUT$。
    /// 双击运行时两者都不可用，此时静默返回。
    /// </summary>
    private static void WriteLines(params string[] lines)
    {
        NativeMethods.AttachConsole(0xFFFFFFFF);

        // 输出被重定向（> file / 管道）时走标准输出；否则直接写控制台
        try
        {
            foreach (string line in lines) Console.Out.WriteLine(line);
            Console.Out.Flush();
        }
        catch (IOException)
        {
        }

        try
        {
            using var stream = new FileStream("CONOUT$", FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            using var writer = new StreamWriter(stream) { AutoFlush = true };
            foreach (string line in lines) writer.WriteLine(line);
        }
        catch (IOException)
        {
            // 没有控制台可写，忽略
        }
        catch (UnauthorizedAccessException)
        {
            // 同上
        }
    }

    private static void ShowFatal(Exception? ex)
    {
        RecordingGuard.EmergencyRestore();
        string message = ex?.Message ?? "未知错误";
        MessageBox.Show(
            $"采集器遇到未处理的错误：\n\n{message}\n\n如果反复出现，请把上面的信息反馈给维护者。",
            "触控板 → RPE",
            MessageBoxButtons.OK,
            MessageBoxIcon.Error);
    }
}
