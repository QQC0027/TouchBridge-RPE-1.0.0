package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.TouchSample
import kotlin.math.hypot

/**
 * 采集层逻辑，对应 Windows 端 GestureEngine.cs 的槽位分配与 tap/drag/hold 判定。
 * 与 PC 版一致：保持 5 个槽位、抬起后残留 0.45s，tick 以帧时间驱动。
 */
class CollectorEngine(private val tracker: GestureTracker) {

    companion object {
        const val MAX_SLOTS = 5
        const val RELEASE_LINGER = 0.45
        const val TAP_MAX_DURATION = 0.2
        const val TAP_MAX_DISTANCE = 0.02
        const val DRAG_MIN_DIST = 0.02
        const val DRAG_AFTER_DUR = 0.12
        const val HOLD_AFTER_DUR = 0.22
        const val MOVE_SEND_INTERVAL = 0.012
    }

    enum class Phase { ACTIVE, RELEASED }

    data class SlotView(
        val slot: Int,
        val x: Double,
        val y: Double,
        val startX: Double,
        val startY: Double,
        val phase: Phase,
        val duration: Double,
    )

    private class Slot(val id: Int) {
        var downT = 0.0
        var sx = 0.0
        var sy = 0.0
        var x = 0.0
        var y = 0.0
        var area = 0.0
        var lastMoveSend = 0.0
        var maxDist = 0.0
        var dragging = false
        var holdSent = false
        var releasedAt = -1.0
        var pointerId = -1
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private var lastFrameTime = 0.0

    fun reset() {
        slots.clear()
        lastFrameTime = 0.0
    }

    private fun allocateSlot(): Int {
        for (i in 1..MAX_SLOTS) {
            if (!slots.containsKey(i)) return i
        }
        // 全占用时复用最老的已释放槽位
        var oldest = 1
        var oldestT = Double.MAX_VALUE
        for ((k, v) in slots) {
            if (v.releasedAt > 0 && v.releasedAt < oldestT) {
                oldestT = v.releasedAt
                oldest = k
            }
        }
        slots.remove(oldest)
        return oldest
    }

    /**
     * 输入一帧触点（仅包含当前仍按下的手指）。同一帧内新增触点会依次 down，
     * 消失的触点会先结算。时间单位为秒。
     */
    fun tick(samples: List<TouchSample>, timeSec: Double) {
        val prevTime = lastFrameTime
        val dt = if (prevTime > 0 && timeSec > prevTime) timeSec - prevTime else 1.0 / 120.0
        lastFrameTime = timeSec

        // 1) 清理已释放且超时残留的槽位
        val expired = slots.filterValues { it.releasedAt > 0 && timeSec - it.releasedAt >= RELEASE_LINGER }
        for (k in expired.keys) slots.remove(k)

        // 2) 结算消失的触点（按 pointerId 匹配）
        val currentIds = samples.map { it.pointerId }.toHashSet()
        for (s in slots.values.toList()) {
            if (s.releasedAt < 0 && s.pointerId >= 0 && s.pointerId !in currentIds) {
                release(s, timeSec)
            }
        }

        // 3) 新增触点
        for (sample in samples) {
            val existing = slots.values.firstOrNull { it.pointerId == sample.pointerId && it.releasedAt < 0 }
            if (existing == null) {
                val id = allocateSlot()
                val s = Slot(id)
                s.pointerId = sample.pointerId
                s.downT = timeSec
                s.sx = sample.x.coerceIn(0.0, 1.0)
                s.sy = sample.y.coerceIn(0.0, 1.0)
                s.x = s.sx
                s.y = s.sy
                s.area = sample.area
                s.lastMoveSend = timeSec
                slots[id] = s
                tracker.onDown(id, s.sx, s.sy, s.area, zoneOf(s.sx))
            }
        }

        // 4) 更新仍按下的触点
        for (s in slots.values) {
            if (s.releasedAt >= 0) continue
            val sample = samples.firstOrNull { it.pointerId == s.pointerId } ?: continue
            val dur = timeSec - s.downT
            s.x = sample.x.coerceIn(0.0, 1.0)
            s.y = sample.y.coerceIn(0.0, 1.0)
            s.area = sample.area

            val dist = hypot(s.x - s.sx, s.y - s.sy)
            if (dist > s.maxDist) s.maxDist = dist

            if (!s.dragging && dist > DRAG_MIN_DIST && dur > DRAG_AFTER_DUR) {
                s.dragging = true
                tracker.onDrag(s.id, s.x, s.y)
            }
            if (!s.dragging && !s.holdSent && dur > HOLD_AFTER_DUR) {
                s.holdSent = true
                tracker.onHold(s.id, s.x, s.y)
            }
            if (timeSec - s.lastMoveSend >= MOVE_SEND_INTERVAL) {
                s.lastMoveSend = timeSec
                tracker.onMove(s.id, s.x, s.y, s.area)
            }
        }
        // dt 仅用于保持时间推进的语义，避免编译器警告
        if (dt < 0) return
    }

    private fun release(s: Slot, timeSec: Double) {
        s.releasedAt = timeSec
        val dur = timeSec - s.downT
        if (s.maxDist < TAP_MAX_DISTANCE && dur < TAP_MAX_DURATION) {
            tracker.onTap(s.id, s.x, s.y, dur * 1000.0)
        } else {
            tracker.onUp(s.id, dur * 1000.0, s.maxDist)
        }
    }

    fun snapshot(timeSec: Double): List<SlotView> = slots.map { (k, s) ->
        SlotView(
            slot = k,
            x = s.x,
            y = s.y,
            startX = s.sx,
            startY = s.sy,
            phase = if (s.releasedAt >= 0) Phase.RELEASED else Phase.ACTIVE,
            duration = if (s.releasedAt >= 0) s.releasedAt - s.downT else timeSec - s.downT,
        )
    }

    private fun zoneOf(x: Double): Int = (x * 9).toInt().coerceIn(0, 8)
}
