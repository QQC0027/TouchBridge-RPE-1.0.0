namespace TouchBridge.UI;

/// <summary>
/// 统一的 DPI 度量：WinForms 的 DeviceDpi 在窗口刚创建时可能仍是 96，
/// 这里直接向窗口句柄查询，保证与所在显示器一致。
/// </summary>
internal static class UiMetrics
{
    internal const float BaseDpi = 96f;

    internal static float ScaleOf(Control control)
    {
        int dpi = control.IsHandleCreated ? Native.NativeMethods.GetDpiForWindow(control.Handle) : (int)BaseDpi;
        if (dpi <= 0) dpi = (int)BaseDpi;
        return dpi / BaseDpi;
    }
}
