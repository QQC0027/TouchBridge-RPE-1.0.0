using System.Text.Json;
using System.Windows.Forms;
using TouchBridge.Core;
using TouchBridge.Native;

namespace TouchBridge;

/// <summary>
/// 触控板采集器（一体化版）。默认以无窗口管道模式运行，由 TouchBridge → RPE 主程序
/// 自动拉起：stdout 输出 JSON 事件，stdin 接收命令。正常使用不需要单独启动它。
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        // 上次异常退出若遗留触控板手势备份，先恢复，避免系统设置被永久改写
        RecordingGuard.EmergencyRestore();
        if (args.Any(a => a.Equals("--restore", StringComparison.OrdinalIgnoreCase))) return 0;

        if (args.Any(a => a.Equals("--diagnose", StringComparison.OrdinalIgnoreCase)))
        {
            RunDiagnose();
            return 0;
        }

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        using var pipe = new PipeChannel();
        pipe.Event("hello", writer =>
        {
            writer.WriteNumber("pid", Environment.ProcessId);
            writer.WriteNumber("protocol", 1);
            writer.WriteString("version", "1.1");
        });

        Application.ThreadException += (_, e) => ReportFatal(e.Exception, pipe);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => ReportFatal(e.ExceptionObject as Exception, pipe);

        int exitCode = 0;
        try
        {
            using var host = new CollectorHost(pipe);
            Application.Run(host);
        }
        catch (Exception ex)
        {
            ReportFatal(ex, pipe);
            exitCode = 1;
        }
        finally
        {
            // 双保险：无论正常退出还是异常，都恢复被临时改写的系统设置
            RecordingGuard.EmergencyRestore();
        }

        return exitCode;
    }

    /// <summary>把致命错误写成 JSON 交给主程序（无窗口模式下不能用 MessageBox，会阻塞）。</summary>
    private static void ReportFatal(Exception? exception, PipeChannel pipe)
    {
        RecordingGuard.EmergencyRestore();
        string message = exception?.Message ?? "未知错误";
        pipe.Event("error", writer =>
        {
            writer.WriteString("message", message);
            writer.WriteBoolean("fatal", true);
            writer.WriteString("detail", exception?.ToString() ?? string.Empty);
        });
    }

    /// <summary>诊断模式：监听 3 秒，把设备描述符摘要与前若干帧写入临时文件。</summary>
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
            foreach (TouchContact contact in frame.Contacts)
            {
                sb.Append($"[id={contact.ContactId} x={contact.X:0.00} y={contact.Y:0.00} tip={(contact.Tip ? 1 : 0)}] ");
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

        try
        {
            string path = Path.Combine(Path.GetTempPath(), "touchbridge-diagnose.txt");
            File.WriteAllText(path, sb.ToString(), System.Text.Encoding.UTF8);
            WriteLines($"diagnose written to {path}");
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    /// <summary>
    /// WinExe 没有自带控制台：先尝试附加父进程控制台，再直接写 CONOUT$。
    /// 双击运行时两者都不可用，此时静默返回。
    /// </summary>
    private static void WriteLines(params string[] lines)
    {
        NativeMethods.AttachConsole(0xFFFFFFFF);

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
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}