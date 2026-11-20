using System.Drawing.Drawing2D;
using TouchBridge.Core;

namespace TouchBridge.UI;

/// <summary>
/// 自绘状态面板：触控板 3×3 区域图 + 5 个手指槽位 + 最近消息。
/// 只读取外部传入的快照，不引用引擎，便于单独调试。
/// 所有像素常量按 96 DPI 设计，绘制时乘以 <see cref="S"/> 适配系统缩放。
/// </summary>
public sealed class TouchPanel : Control
{
    public static class Pal
    {
        public static readonly Color Bg = Color.FromArgb(11, 11, 15);
        public static readonly Color Card = Color.FromArgb(20, 20, 27);
        public static readonly Color Accent = Color.FromArgb(107, 214, 250);
        public static readonly Color Text = Color.FromArgb(235, 235, 240);
        public static readonly Color Dim = Color.FromArgb(255, 255, 255).WithAlpha(0.45f);
        public static readonly Color Faint = Color.FromArgb(255, 255, 255).WithAlpha(0.22f);
        public static readonly Color Ok = Color.FromArgb(92, 219, 158);
        public static readonly Color Warn = Color.FromArgb(250, 168, 77);
    }

    private IReadOnlyList<SlotView> _slots = Array.Empty<SlotView>();
    private IReadOnlyList<string> _log = Array.Empty<string>();

    private float S => UiMetrics.ScaleOf(this);

    public TouchPanel()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        BackColor = Pal.Bg;
        ForeColor = Pal.Text;
        Font = new Font("Microsoft YaHei UI", 9f);
    }

    public void UpdateState(IReadOnlyList<SlotView> slots, IReadOnlyList<string> log)
    {
        _slots = slots;
        _log = log;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        using (var bg = new SolidBrush(Pal.Bg)) g.FillRectangle(bg, ClientRectangle);

        float s = S;
        int top = (int)(40 * s);
        int padW = (int)Math.Min(300 * s, Math.Max(180 * s, Width * 0.44f));
        int padH = (int)(padW * 22f / 30f);
        var pad = new Rectangle(Padding.Left, top, padW, padH);

        DrawLabel(g, "触控板 3×3 区域", pad.Left, (int)(18 * s));
        DrawPad(g, pad);
        DrawFingers(g, pad);

        int listX = pad.Right + (int)(36 * s);
        int listW = Width - listX - (int)(20 * s);
        DrawLabel(g, "手指槽位", listX, (int)(18 * s));

        int rowH = (int)(36 * s);
        int gap = (int)(8 * s);
        for (int i = 1; i <= GestureThresholds.MaxSlots; i++)
        {
            var row = new Rectangle(listX, top + (i - 1) * (rowH + gap), listW, rowH);
            DrawSlot(g, i, row);
        }

        int logTop = top + GestureThresholds.MaxSlots * (rowH + gap) + gap;
        DrawLabel(g, "最近消息", listX, logTop);
        int ly = logTop + (int)(22 * s);
        int step = (int)(17 * s);
        int logBottom = Height - (int)(40 * s);
        using (var logFont = new Font("Consolas", 9f))
        using (var logBrush = new SolidBrush(Color.FromArgb(255, 255, 255).WithAlpha(0.38f)))
        {
            // 新的在上；按剩余高度铺满，窗口放大时能看到更多历史
            for (int i = _log.Count - 1; i >= 0 && ly + step <= logBottom; i--)
            {
                g.DrawString(_log[i], logFont, logBrush, listX, ly);
                ly += step;
            }
        }

        DrawLabel(g, "轻点 / 长按 / 滑动 会被识别为 /tap /hold /drag /move，并转发给 RPE",
            pad.Left, Height - (int)(28 * s));
    }

    private void DrawPad(Graphics g, Rectangle pad)
    {
        using (var fill = new SolidBrush(Color.FromArgb(255, 255, 255).WithAlpha(0.03f)))
            g.FillRectangle(fill, pad);

        using var grid = new Pen(Color.FromArgb(255, 255, 255).WithAlpha(0.09f), 1f);
        for (int i = 1; i < 3; i++)
        {
            int gx = pad.Left + pad.Width * i / 3;
            g.DrawLine(grid, gx, pad.Top, gx, pad.Bottom);
            int gy = pad.Top + pad.Height * i / 3;
            g.DrawLine(grid, pad.Left, gy, pad.Right, gy);
        }
        using var border = new Pen(Color.FromArgb(255, 255, 255).WithAlpha(0.16f), 1f);
        g.DrawRectangle(border, pad);
    }

    private void DrawFingers(Graphics g, Rectangle pad)
    {
        float s = S;
        foreach (SlotView slot in _slots)
        {
            float alpha = slot.ReleasedAgoSeconds < 0
                ? 1f
                : Math.Max(0f, 1f - (float)slot.ReleasedAgoSeconds / 0.45f);
            if (alpha <= 0.01f) continue;

            // 快照坐标原点与 WPT 一致（左上），直接线性映射
            int px = pad.Left + (int)(Math.Clamp(slot.X, 0f, 1f) * pad.Width);
            int py = pad.Top + (int)(Math.Clamp(slot.Y, 0f, 1f) * pad.Height);

            int glow = (int)(36 * s);
            int ring = (int)(20 * s);
            using (var brush = new SolidBrush(Color.FromArgb((int)(38 * alpha), Pal.Accent)))
                g.FillEllipse(brush, px - glow / 2, py - glow / 2, glow, glow);
            using (var pen = new Pen(Color.FromArgb((int)(225 * alpha), Pal.Accent), 1.6f))
                g.DrawEllipse(pen, px - ring / 2, py - ring / 2, ring, ring);

            using var f = new Font("Consolas", 9f, FontStyle.Bold);
            using var b = new SolidBrush(Color.FromArgb((int)(235 * alpha), Pal.Text));
            string label = slot.Slot.ToString();
            SizeF sz = g.MeasureString(label, f);
            g.DrawString(label, f, b, px - sz.Width / 2f, py - sz.Height / 2f);
        }
    }

    private void DrawSlot(Graphics g, int index, Rectangle row)
    {
        SlotView? slot = null;
        foreach (SlotView s in _slots)
        {
            if (s.Slot == index) { slot = s; break; }
        }

        float scale = S;
        using (GraphicsPath path = Rounded(row, (int)(8 * scale)))
        using (var fill = new SolidBrush(slot is null ? Color.FromArgb(255, 255, 255).WithAlpha(0.05f) : Pal.Card))
        {
            g.FillPath(fill, path);
        }

        int padX = (int)(12 * scale);
        int textY = row.Top + (int)(9 * scale);

        using var idxFont = new Font("Consolas", 10.5f, FontStyle.Bold);
        using var idxBrush = new SolidBrush(slot is null ? Pal.Faint : Pal.Accent);
        g.DrawString(index.ToString(), idxFont, idxBrush, row.Left + padX, textY);

        int nameX = row.Left + (int)(42 * scale);
        int phaseX = row.Left + (int)(104 * scale);

        if (slot is { } active)
        {
            int zone = GestureEngine.ZoneIndex(active.X, active.Y);
            using var f = new Font("Microsoft YaHei UI", 9f);
            using var text = new SolidBrush(Pal.Text);
            using var dim = new SolidBrush(Pal.Dim);
            g.DrawString(GestureEngine.ZoneName(zone), f, text, nameX, textY);
            g.DrawString(active.PhaseText, f, dim, phaseX, textY);

            using var mono = new Font("Consolas", 8.5f);
            string coords = $"x {active.X:0.00}   y {active.Y:0.00}";
            SizeF sz = g.MeasureString(coords, mono);
            g.DrawString(coords, mono, dim, row.Right - sz.Width - padX, textY + (int)(2 * scale));
        }
        else
        {
            using var f = new Font("Microsoft YaHei UI", 9f);
            using var faint = new SolidBrush(Pal.Faint);
            g.DrawString("空闲", f, faint, nameX, textY);
        }
    }

    private void DrawLabel(Graphics g, string text, int x, int y)
    {
        using var f = new Font("Microsoft YaHei UI", 8f);
        using var b = new SolidBrush(Pal.Dim);
        g.DrawString(text, f, b, x, y);
    }

    private static GraphicsPath Rounded(Rectangle r, int radius)
    {
        int d = radius * 2;
        var path = new GraphicsPath();
        path.AddArc(r.Left, r.Top, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}

internal static class ColorExtensions
{
    internal static Color WithAlpha(this Color color, float alpha)
        => Color.FromArgb((int)Math.Clamp(alpha * 255f, 0, 255), color.R, color.G, color.B);
}
