package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TrackPoint
import kotlin.math.hypot

/**
 * 手势分类器，逐行对应 backend/engine.py 的 GestureTracker。
 * 输入为采集层解析出的 /down /move /hold /drag /tap /up 语义。
 */
class GestureTracker(
    private val getTime: () -> Double,
    private val settingsProvider: () -> ChartSettings,
) {
    private class Slot(
        val downT: Double,
        val sx: Double,
        val sy: Double,
        var lastT: Double,
        var lastX: Double,
        var lastY: Double,
        var peakVel: Double,
        var maxDist: Double,
        val traj: MutableList<TrackPoint> = mutableListOf(),
        var dragging: Boolean = false,
    )

    private val slots = LinkedHashMap<Int, Slot>()
    val completed = mutableListOf<GestureNote>()
    private var gid = 0

    private val settings: ChartSettings get() = settingsProvider()

    fun reset() {
        slots.clear()
        completed.clear()
        gid = 0
    }

    private fun finalize(
        slot: Int,
        gesture: Gesture,
        tSec: Double,
        x: Double,
        holdSec: Double = 0.0,
        path: List<TrackPoint> = emptyList(),
        dir: Pair<Double, Double>? = null,
    ) {
        completed.add(
            GestureNote(
                gesture = gesture,
                finger = slot,
                tSec = tSec,
                x = x,
                holdSec = holdSec,
                path = path,
                dir = dir,
                gid = gid,
            ),
        )
        gid += 1
        slots.remove(slot)
    }

    private fun classify(s: Slot, dur: Double, maxDist: Double): GestureNote {
        val t0 = s.downT
        val last = s.traj.lastOrNull() ?: TrackPoint(t0, s.sx, s.sy)
        val lx = last.x
        val ly = last.y
        val cfg = settings

        if (dur <= cfg.flickWindow && s.peakVel >= cfg.flickSpeed && maxDist <= cfg.flickMaxDist) {
            val dx = lx - s.sx
            val dy = ly - s.sy
            val nrm = hypot(dx, dy).let { if (it == 0.0) 1e-6 else it }
            return GestureNote(
                gesture = Gesture.FLICK,
                finger = 0,
                tSec = t0,
                x = lx,
                dir = Pair(dx / nrm, dy / nrm),
                gid = 0,
            )
        }

        val avgSpeed = if (dur > 1e-4) maxDist / dur else 0.0
        if (avgSpeed < cfg.dragMinSpeed || dur > cfg.dragMaxDur) {
            return GestureNote(
                gesture = Gesture.HOLD,
                finger = 0,
                tSec = t0,
                x = s.sx,
                holdSec = dur,
                gid = 0,
            )
        }

        return GestureNote(
            gesture = Gesture.DRAG,
            finger = 0,
            tSec = t0,
            x = s.sx,
            path = s.traj.toList(),
            gid = 0,
        )
    }

    fun onDown(slot: Int, x: Double, y: Double, area: Double, zone: Int) {
        val t = getTime()
        slots[slot] = Slot(
            downT = t,
            sx = x,
            sy = y,
            lastT = t,
            lastX = x,
            lastY = y,
            peakVel = 0.0,
            maxDist = 0.0,
        ).also { it.traj.add(TrackPoint(t, x, y)) }
    }

    fun onMove(slot: Int, x: Double, y: Double, area: Double) {
        val s = slots[slot] ?: return
        val t = getTime()
        val dt = t - s.lastT
        val dx = x - s.lastX
        val dy = y - s.lastY
        val seg = hypot(dx, dy)
        if (dt > 1e-4) {
            val vel = seg / dt
            if (vel > s.peakVel) s.peakVel = vel
        }
        val distFromStart = hypot(x - s.sx, y - s.sy)
        if (distFromStart > s.maxDist) s.maxDist = distFromStart
        s.lastT = t
        s.lastX = x
        s.lastY = y
        s.traj.add(TrackPoint(t, x, y))
    }

    fun onHold(slot: Int, x: Double, y: Double) {
        slots[slot]?.dragging = true
    }

    fun onDrag(slot: Int, x: Double, y: Double) {
        slots[slot]?.dragging = true
    }

    fun onTap(slot: Int, x: Double, y: Double, durMs: Double) {
        val s = slots[slot]
        if (s != null) {
            finalize(slot, Gesture.TAP, s.downT, s.sx)
        } else {
            finalize(slot, Gesture.TAP, getTime(), x)
        }
    }

    fun onUp(slot: Int, durMs: Double, maxDist: Double) {
        val s = slots[slot] ?: return
        val dur = durMs / 1000.0
        val dist = maxOf(s.maxDist, maxDist)
        val cfg = settings
        if (dist < cfg.deadZone) {
            if (dur >= cfg.holdMinTime) {
                finalize(slot, Gesture.HOLD, s.downT, s.sx, holdSec = dur)
            } else {
                finalize(slot, Gesture.TAP, s.downT, s.sx)
            }
        } else {
            val partial = classify(s, dur, dist)
            finalize(
                slot = slot,
                gesture = partial.gesture,
                tSec = partial.tSec,
                x = partial.x,
                holdSec = partial.holdSec,
                path = partial.path,
                dir = partial.dir,
            )
        }
    }

    fun flushOpen() {
        val now = getTime()
        val entries = slots.entries.toList()
        for ((slot, s) in entries) {
            val dur = now - s.downT
            val maxDist = s.maxDist
            val cfg = settings
            if (maxDist < cfg.deadZone) {
                if (dur >= cfg.holdMinTime) {
                    finalize(slot, Gesture.HOLD, s.downT, s.sx, holdSec = dur)
                } else {
                    finalize(slot, Gesture.TAP, s.downT, s.sx)
                }
            } else {
                val partial = classify(s, dur, maxDist)
                finalize(
                    slot = slot,
                    gesture = partial.gesture,
                    tSec = partial.tSec,
                    x = partial.x,
                    holdSec = partial.holdSec,
                    path = partial.path,
                    dir = partial.dir,
                )
            }
        }
        slots.clear()
    }
}
