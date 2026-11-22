using System.Text.Json;
using Microsoft.Win32;
using TouchBridge.Native;

namespace TouchBridge.Core;

public enum RecordLockState
{
    Idle,
    Locked,
    Unlocked,
}

/// <summary>
/// 录制专注模式状态机：隐藏/固定鼠标、ESC 解除、Alt+Enter 重新锁定、
/// 临时禁用精确式触控板多指手势，并在停止录制或异常退出时完整恢复。
/// 所有方法都应在 UI 线程调用。
/// </summary>
public sealed unsafe class RecordingGuard
{
    private const string GestureKeyPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\PrecisionTouchPad";

    // 只禁用会触发系统级操作的多指手势；单指/双指原始触点不受影响，采集照常工作。
    private static readonly string[] GestureValueNames =
    {
        "ThreeFingerSlideEnabled",
        "FourFingerSlideEnabled",
        "ThreeFingerTapEnabled",
        "FourFingerTapEnabled",
    };

    private static readonly string BackupPath =
        Path.Combine(Path.GetTempPath(), "touchbridge-gesture-backup.json");

    private const int HotkeyUnlock = 1;
    private const int HotkeyRelock = 2;
    private const int ShowCursorAttempts = 32;

    public RecordLockState State { get; private set; } = RecordLockState.Idle;

    /// <summary>降级原因（热键冲突或注册表写入失败），供界面提示。</summary>
    public string? Warning { get; private set; }

    private nint _hwnd;
    private bool _escRegistered;
    private bool _altEnterRegistered;
    private Dictionary<string, int?>? _gestureBackup;

    /// <summary>进入录制专注模式：禁用手势 + 锁定鼠标 + 注册全局热键。</summary>
    public bool EnterRecording(nint hwnd)
    {
        if (State != RecordLockState.Idle)
        {
            LockMouse();
            return true;
        }

        _hwnd = hwnd;
        Warning = null;
        RestoreStaleBackupOnStart();

        DisableGestures();
        RegisterHotkeys();
        LockMouse();
        return true;
    }

    /// <summary>退出录制专注模式：完整恢复所有系统状态。</summary>
    public void ExitRecording()
    {
        if (State == RecordLockState.Idle) return;

        ShowAndFreeCursor();
        UnregisterHotkeys();
        RestoreGestures();
        State = RecordLockState.Idle;
        _hwnd = nint.Zero;
    }

    /// <summary>Alt+Enter：重新隐藏并固定鼠标。</summary>
    public void LockMouse()
    {
        if (State == RecordLockState.Locked) return;

        if (NativeMethods.GetCursorPos(out var pos))
        {
            var rect = new NativeMethods.Rect
            {
                Left = pos.X,
                Top = pos.Y,
                Right = pos.X + 1,
                Bottom = pos.Y + 1,
            };
            NativeMethods.ClipCursor(&rect);
        }
        else
        {
            Warning = AppendWarning(Warning, "无法读取鼠标位置，只隐藏了光标");
        }

        HideCursor();
        State = RecordLockState.Locked;
    }

    /// <summary>ESC：临时解除鼠标锁定，录制与手势禁用继续。</summary>
    public void UnlockMouse()
    {
        if (State != RecordLockState.Locked) return;
        ShowAndFreeCursor();
        State = RecordLockState.Unlocked;
    }

    /// <summary>由 MainForm.WndProc 转发 WM_HOTKEY 的快捷键 ID。</summary>
    public void HandleHotkey(int id)
    {
        if (State == RecordLockState.Idle) return;
        if (id == HotkeyUnlock) UnlockMouse();
        else if (id == HotkeyRelock) LockMouse();
    }

    private static void ShowAndFreeCursor()
    {
        ShowCursorIfHidden();
        NativeMethods.ClipCursor(null);
    }

    private void RegisterHotkeys()
    {
        _escRegistered = NativeMethods.RegisterHotKey(_hwnd, HotkeyUnlock, 0, NativeMethods.VkEscape);
        _altEnterRegistered = NativeMethods.RegisterHotKey(_hwnd, HotkeyRelock, NativeMethods.ModAlt, NativeMethods.VkReturn);

        if (!_escRegistered && !_altEnterRegistered)
        {
            Warning = AppendWarning(Warning, "ESC / Alt+Enter 注册失败（可能被其它程序占用），请用「停止录制」恢复");
        }
        else if (!_escRegistered)
        {
            Warning = AppendWarning(Warning, "ESC 注册失败，无法解除鼠标锁定；请点「停止录制」");
        }
        else if (!_altEnterRegistered)
        {
            Warning = AppendWarning(Warning, "Alt+Enter 注册失败，解除后无法重新锁定");
        }
    }

    private void UnregisterHotkeys()
    {
        if (_escRegistered) NativeMethods.UnregisterHotKey(_hwnd, HotkeyUnlock);
        if (_altEnterRegistered) NativeMethods.UnregisterHotKey(_hwnd, HotkeyRelock);
        _escRegistered = false;
        _altEnterRegistered = false;
    }

    private static void HideCursor()
    {
        // ShowCursor 是引用计数，循环到返回负值才真正隐藏，避免多次进入后计数残留。
        for (int i = 0; i < ShowCursorAttempts; i++)
        {
            if (NativeMethods.ShowCursor(false) < 0) break;
        }
    }

    private static void ShowCursorIfHidden()
    {
        for (int i = 0; i < ShowCursorAttempts; i++)
        {
            if (NativeMethods.ShowCursor(true) >= 0) break;
        }
    }

    private void DisableGestures()
    {
        if (_gestureBackup is not null) return;
        try
        {
            Dictionary<string, int?> backup = ReadGestureValues();
            File.WriteAllText(BackupPath, JsonSerializer.Serialize(backup));
            ApplyGestureValues(backup, disabled: true);
            _gestureBackup = backup;
        }
        catch (Exception ex)
        {
            Warning = AppendWarning(Warning, $"触控板手势禁用失败：{ex.Message}");
        }
    }

    private void RestoreGestures()
    {
        if (_gestureBackup is null) return;
        try
        {
            ApplyGestureValues(_gestureBackup, disabled: false);
        }
        catch
        {
            // 保留备份文件，供下次启动或 EmergencyRestore 重试，避免系统设置被永久改写。
            return;
        }
        _gestureBackup = null;
        ClearBackupFile();
    }

    private static Dictionary<string, int?> ReadGestureValues()
    {
        var result = new Dictionary<string, int?>();
        using var key = Registry.CurrentUser.OpenSubKey(GestureKeyPath);
        if (key is null) return result;

        foreach (string name in GestureValueNames)
        {
            object? value = key.GetValue(name, null, RegistryValueOptions.None);
            result[name] = value is int i ? i : null;
        }
        return result;
    }

    private static void ApplyGestureValues(Dictionary<string, int?> values, bool disabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(GestureKeyPath, true);
        if (key is null) return;

        foreach (KeyValuePair<string, int?> item in values)
        {
            if (disabled)
            {
                key.SetValue(item.Key, 0, RegistryValueKind.DWord);
            }
            else if (item.Value.HasValue)
            {
                key.SetValue(item.Key, item.Value.Value, RegistryValueKind.DWord);
            }
            else
            {
                // 原本不存在的值：删除我们创建的项，让系统恢复默认行为。
                key.DeleteValue(item.Key, false);
            }
        }

        _ = NativeMethods.SendMessageTimeout(
            NativeMethods.HwndBroadcast,
            NativeMethods.WmSettingChange,
            nint.Zero,
            "PrecisionTouchPad",
            NativeMethods.SmtoAbortifhung,
            1000,
            out _);
    }

    private static Dictionary<string, int?>? ReadBackup()
    {
        if (!File.Exists(BackupPath)) return null;
        try
        {
            return JsonSerializer.Deserialize<Dictionary<string, int?>>(File.ReadAllText(BackupPath));
        }
        catch
        {
            return null;
        }
    }

    private static void ClearBackupFile()
    {
        if (!File.Exists(BackupPath)) return;
        File.Delete(BackupPath);
    }

    /// <summary>启动时若发现上次崩溃遗留的备份，先恢复系统设置。</summary>
    private static void RestoreStaleBackupOnStart()
    {
        Dictionary<string, int?>? stale = ReadBackup();
        if (stale is null) return;
        try
        {
            ApplyGestureValues(stale, disabled: false);
            ClearBackupFile();
        }
        catch
        {
            // 下次启动继续尝试
        }
    }

    /// <summary>异常退出兜底：从备份恢复手势、解除鼠标锁定，供 Program 的全局异常处理调用。</summary>
    public static void EmergencyRestore()
    {
        try
        {
            Dictionary<string, int?>? backup = ReadBackup();
            if (backup is not null)
            {
                ApplyGestureValues(backup, disabled: false);
                ClearBackupFile();
            }
            ShowCursorIfHidden();
            NativeMethods.ClipCursor(null);
        }
        catch
        {
            // 进程即将退出，不抛出以免掩盖原始异常
        }
    }

    private static string AppendWarning(string? existing, string addition)
        => string.IsNullOrEmpty(existing) ? addition : existing + "；" + addition;
}
