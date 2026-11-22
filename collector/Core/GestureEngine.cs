namespace TouchBridge.Core;

/// <summary>手势阈值，与旧版 macOS 实现逐项对齐，改动会直接影响谱面观感。</summary>
public static class GestureThresholds
{
    public const double TapMaxDuration = 0.20;      // /tap: dur < 0.20s
    public const double TapMaxDistance = 0.02;      // /tap: maxDist < 0.02
    public const double DragMinDuration = 0.12;     // /drag: dur > 0.12s
    public const double DragMinDistance = 0.02;     // /drag: dist > 0.02
    public const double HoldMinDuration = 0.22;     // /hold: dur > 0.22s
    public const double MoveInterval = 0.012;       // /move 节流 ≈83Hz
    public const double SlotReleaseLinger = 0.45;   // 槽位淡出后清理
    public const int MaxSlots = 5;                  // slot 1..5
}

public enum SlotPhase
{
    Pressing,
    Dragging,
    Holding,
    Tapped,
    Released,
    Idle,
}

/// <summary>槽位的对外快照，供界面只读展示。</summary>
public readonly record struct SlotView(
    int Slot,
    float X,
    float Y,
    float Area,
    SlotPhase Phase,
    string PhaseText,
    double ReleasedAgoSeconds);

/// <summary>
/// 手势状态机：输入是触点快照，输出是 OSC 消息。逐行移植旧版 tick() 的判定顺序，
/// 时间统一使用 Stopwatch 单调时钟。
/// </summary>
public sealed class GestureEngine
{
    private sealed class Slot
    {
        internal int Id;
        internal double Start;
        internal double Now;
        internal float X;
        internal float Y;
        internal float Area;
        internal float MaxDist;
        internal float StartX;
        internal float StartY;
        internal bool Dragging;
        internal bool HoldSent;
        internal SlotPhase Phase = SlotPhase.Pressing;
        internal double LastMoveSend;
        internal double ReleasedAt = -1;
    }

    private readonly object _sync = new();
    private readonly Dictionary<int, Slot> _slots = new(GestureThresholds.MaxSlots);
    private readonly List<string> _log = new(64);
    private readonly int[] _removeKeys = new int[GestureThresholds.MaxSlots + 1];

    private int _lastCount = -1;
    private long _lastTimestampTicks;

    public ITouchSink Sender { get; }
    public bool Sending { get; set; } = true;
    public bool TrackpadOk { get; set; }
    public string DeviceSummary { get; set; } = "未发现精确式触控板";

    /// <summary>最近一次报文相对设备扫描时间的延迟（毫秒），未知时为 0。</summary>
    public double LatencyMs { get; private set; }

    public int ActiveCount { get; private set; }

    public GestureEngine(ITouchSink sender)
    {
        Sender = sender;
    }

    private static double NowSeconds() => System.Diagnostics.Stopwatch.GetTimestamp() / (double)System.Diagnostics.Stopwatch.Frequency;

    private static double SecondsBetween(long fromTicks, long toTicks)
        => (toTicks - fromTicks) / (double)System.Diagnostics.Stopwatch.Frequency;

    /// <summary>消费一帧触点。所有调用都发生在界面线程，因此状态无需跨线程同步。</summary>
    public void Tick(ContactFrame frame)
    {
        double now = frame.TimestampTicks > 0
            ? frame.TimestampTicks / (double)System.Diagnostics.Stopwatch.Frequency
            : NowSeconds();

        if (_lastTimestampTicks > 0 && frame.TimestampTicks > 0)
        {
            double dt = SecondsBetween(_lastTimestampTicks, frame.TimestampTicks);
            if (dt >= 0 && dt < 0.2) LatencyMs = dt * 1000.0;
        }
        if (frame.TimestampTicks > 0) _lastTimestampTicks = frame.TimestampTicks;

        // 以 ContactId 索引当前按下的触点（同一帧内多设备已去重）
        Span<TouchContact> contacts = stackalloc TouchContact[32];
        int pressedCount = 0;
        ReadOnlySpan<TouchContact> src = frame.Contacts;
        foreach (TouchContact c in src)
        {
            if (!c.Tip) continue;
            if (pressedCount >= contacts.Length) break;
            contacts[pressedCount] = c;
            pressedCount++;
        }

        lock (_sync)
        {
            // 新手指
            for (int i = 0; i < pressedCount; i++)
            {
                TouchContact t = contacts[i];
                bool exists = false;
                foreach (Slot s in _slots.Values)
                {
                    if (s.Id == t.ContactId && s.ReleasedAt < 0) { exists = true; break; }
                }
                if (exists) continue;

                int slotIndex = FreeSlot();
                if (slotIndex < 0) continue;

                var slot = new Slot
                {
                    Id = t.ContactId,
                    Start = now,
                    Now = now,
                    X = t.X,
                    Y = t.Y,
                    Area = t.Area,
                    StartX = t.X,
                    StartY = t.Y,
                    LastMoveSend = now,
                };
                _slots[slotIndex] = slot;

                if (Sending)
                {
                    int zone = ZoneIndex(t.X, t.Y);
                    Sender.Send("/down", stackalloc[]
                    {
                        OscArg.Int(slotIndex), OscArg.Float(t.X), OscArg.Float(t.Y),
                        OscArg.Float(t.Area), OscArg.Int(zone),
                    });
                    Log($"down {slotIndex} → {ZoneName(zone)}");
                }
            }

            // 更新 / 抬起：只修改 Slot 实例字段，不改动字典结构，可直接遍历
            foreach (KeyValuePair<int, Slot> entry in _slots)
            {
                int slotIndex = entry.Key;
                Slot s = entry.Value;
                int idx = IndexOf(contacts, pressedCount, s.Id);
                if (idx >= 0)
                {
                    TouchContact t = contacts[idx];
                    float dx = t.X - s.StartX;
                    float dy = t.Y - s.StartY;
                    float dist = MathF.Sqrt(dx * dx + dy * dy);
                    if (dist > s.MaxDist) s.MaxDist = dist;

                    s.X = t.X;
                    s.Y = t.Y;
                    s.Now = now;
                    s.Area = t.Area;
                    double dur = now - s.Start;

                    if (!s.Dragging && dist > GestureThresholds.DragMinDistance && dur > GestureThresholds.DragMinDuration)
                    {
                        s.Dragging = true;
                        s.Phase = SlotPhase.Dragging;
                        if (Sending)
                        {
                            Sender.Send("/drag", stackalloc[] { OscArg.Int(slotIndex), OscArg.Float(t.X), OscArg.Float(t.Y) });
                            Log($"drag {slotIndex}");
                        }
                    }

                    if (!s.Dragging && !s.HoldSent && dur > GestureThresholds.HoldMinDuration)
                    {
                        s.HoldSent = true;
                        s.Phase = SlotPhase.Holding;
                        if (Sending)
                        {
                            Sender.Send("/hold", stackalloc[] { OscArg.Int(slotIndex), OscArg.Float(t.X), OscArg.Float(t.Y) });
                            Log($"hold {slotIndex}");
                        }
                    }

                    if (now - s.LastMoveSend > GestureThresholds.MoveInterval)
                    {
                        s.LastMoveSend = now;
                        if (Sending)
                        {
                            Sender.Send("/move", stackalloc[]
                            {
                                OscArg.Int(slotIndex), OscArg.Float(t.X), OscArg.Float(t.Y), OscArg.Float(s.Area),
                            });
                        }
                    }
                }
                else if (s.ReleasedAt < 0)
                {
                    s.ReleasedAt = now;
                    double dur = now - s.Start;
                    bool isTap = dur < GestureThresholds.TapMaxDuration && s.MaxDist < GestureThresholds.TapMaxDistance;
                    if (Sending)
                    {
                        if (isTap)
                        {
                            Sender.Send("/tap", stackalloc[]
                            {
                                OscArg.Int(slotIndex), OscArg.Float(s.X), OscArg.Float(s.Y), OscArg.Float((float)(dur * 1000.0)),
                            });
                            Log($"tap {slotIndex} {(int)(dur * 1000.0)}ms");
                        }
                        else
                        {
                            Sender.Send("/up", stackalloc[]
                            {
                                OscArg.Int(slotIndex), OscArg.Float((float)(dur * 1000.0)), OscArg.Float(s.MaxDist),
                            });
                            Log($"up {slotIndex} {(int)(dur * 1000.0)}ms");
                        }
                    }
                    s.Phase = isTap ? SlotPhase.Tapped : SlotPhase.Released;
                }
            }

            // 清理淡出结束的槽位
            int removeCount = 0;
            foreach (KeyValuePair<int, Slot> entry in _slots)
            {
                Slot s = entry.Value;
                if (s.ReleasedAt > 0 && now - s.ReleasedAt > GestureThresholds.SlotReleaseLinger)
                {
                    _removeKeys[removeCount++] = entry.Key;
                }
            }
            for (int i = 0; i < removeCount; i++)
            {
                _slots.Remove(_removeKeys[i]);
            }

            ActiveCount = pressedCount;
            if (pressedCount != _lastCount)
            {
                _lastCount = pressedCount;
                if (Sending) Sender.Send("/count", stackalloc[] { OscArg.Int(pressedCount) });
            }
        }
    }

    private static int IndexOf(Span<TouchContact> contacts, int count, int contactId)
    {
        for (int i = 0; i < count; i++)
        {
            if (contacts[i].ContactId == contactId) return i;
        }
        return -1;
    }

    private int FreeSlot()
    {
        for (int i = 1; i <= GestureThresholds.MaxSlots; i++)
        {
            if (!_slots.ContainsKey(i)) return i;
        }
        return -1;
    }

    public static int ZoneIndex(float x, float y)
    {
        int cx = Math.Clamp((int)(x * 3f), 0, 2);
        // WPT 原点在左上角，无需像旧版那样翻转 y
        int cy = Math.Clamp((int)(y * 3f), 0, 2);
        return cy * 3 + cx;
    }

    public static string ZoneName(int zone) => zone switch
    {
        0 => "左上",
        1 => "中上",
        2 => "右上",
        3 => "左中",
        4 => "中心",
        5 => "右中",
        6 => "左下",
        7 => "中下",
        _ => "右下",
    };

    internal void Log(string message)
    {
        _log.Add(message);
        if (_log.Count > 60) _log.RemoveRange(0, _log.Count - 60);
    }

    /// <summary>记录一条非 OSC 事件（端口切换、发送失败等），与报文日志共用展示区。</summary>
    public void Note(string message)
    {
        lock (_sync) Log(message);
    }

    /// <summary>读取当前状态快照；在界面线程调用，无需加锁。</summary>
    public List<SlotView> SnapshotSlots()
    {
        lock (_sync)
        {
            double now = NowSeconds();
            var list = new List<SlotView>(_slots.Count);
            foreach ((int slotIndex, Slot s) in _slots)
            {
                double releasedAgo = s.ReleasedAt < 0 ? -1 : Math.Max(0, now - s.ReleasedAt);
                list.Add(new SlotView(slotIndex, s.X, s.Y, s.Area, s.Phase, PhaseText(s.Phase), releasedAgo));
            }
            list.Sort((a, b) => a.Slot.CompareTo(b.Slot));
            return list;
        }
    }

    public List<string> SnapshotLog()
    {
        lock (_sync) return new List<string>(_log);
    }

    public static string PhaseText(SlotPhase phase) => phase switch
    {
        SlotPhase.Pressing => "按下",
        SlotPhase.Dragging => "滑动",
        SlotPhase.Holding => "长按",
        SlotPhase.Tapped => "点击",
        SlotPhase.Released => "松开",
        _ => "空闲",
    };

    public void Reset()
    {
        lock (_sync)
        {
            _slots.Clear();
            _log.Clear();
            _lastCount = -1;
            ActiveCount = 0;
        }
    }
}
