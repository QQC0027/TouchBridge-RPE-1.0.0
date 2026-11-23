package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.RpeNote
import com.touchbridge.rpe.domain.model.Rounder
import com.touchbridge.rpe.domain.model.TrackPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 逐行对应 engine.py 的 convert_notes()：量化、坐标映射、去抖、拖拽平滑与下采样。 */
object NoteConverter {

    private const val NOTE_TAP = 1
    private const val NOTE_HOLD = 2
    private const val NOTE_FLICK = 3
    private const val NOTE_DRAG = 4
    private const val MIN_SAMPLE_SPACING = 0.012

    fun makeNote(
        noteType: Int,
        startBeatRaw: Double,
        endBeatRaw: Double,
        posX: Double,
        clock: BeatClock,
    ): RpeNote {
        val startBeat = Rounder.round(startBeatRaw, 6)
        val endBeat = Rounder.round(endBeatRaw, 6)
        return RpeNote(
            type = noteType,
            startTime = clock.beatToBbt(startBeat),
            endTime = clock.beatToBbt(endBeat),
            positionX = Rounder.round(posX, 3),
            startBeat = startBeat,
            endBeat = endBeat,
        )
    }

    fun convert(notes: List<GestureNote>, clock: BeatClock, settings: ChartSettings): List<RpeNote> {
        val subdivision = settings.quantizeSubdivision()
        val tolerance = settings.snapTolerance
        val debounce = settings.debounceMs / 1000.0
        val xMode = settings.xMode
        val regions = settings.regions
        val dragSampleMs = settings.dragSampleMs
        val offs = settings.hitOffsetMs / 1000.0

        val out = ArrayList<RpeNote>(notes.size)
        val lastFingerBeat = HashMap<Int, Double>()

        for (n in notes) {
            when (n.gesture) {
                Gesture.TAP, Gesture.FLICK -> {
                    val b = Quantizer.quantize(
                        clock.timeToBeat(n.tSec + offs), subdivision, tolerance,
                    )
                    val prev = lastFingerBeat[n.finger]
                    if (prev != null && abs(b - prev) < debounce) continue
                    lastFingerBeat[n.finger] = b
                    val pos = PositionMapper.mapX(n.x, xMode, regions)
                    val type = if (n.gesture == Gesture.TAP) NOTE_TAP else NOTE_FLICK
                    val note = makeNote(type, b, b, pos, clock)
                    out.add(
                        if (n.gesture == Gesture.FLICK && n.dir != null) note.copy(dir = n.dir) else note,
                    )
                }

                Gesture.HOLD -> {
                    val tb = n.tSec + offs
                    val s = Quantizer.quantize(clock.timeToBeat(tb), subdivision, tolerance)
                    var e = Quantizer.quantize(
                        clock.timeToBeat(tb + n.holdSec), subdivision, tolerance,
                    )
                    val prev = lastFingerBeat[n.finger]
                    if (prev != null && abs(s - prev) < debounce) continue
                    lastFingerBeat[n.finger] = s
                    if (e <= s) e = s + 1.0 / max(1, subdivision)
                    val pos = PositionMapper.mapX(n.x, xMode, regions)
                    out.add(makeNote(NOTE_HOLD, s, e, pos, clock))
                }

                Gesture.DRAG -> {
                    val path = n.path
                    val gid = n.gid
                    val sampled: List<TrackPoint> = if (path.isNotEmpty()) {
                        var pts = mutableListOf(path[0])
                        for (i in 1 until path.size) {
                            val p = path[i]
                            val lastPoint = pts.last()
                            if (hypot(p.x - lastPoint.x, p.y - lastPoint.y) >= MIN_SAMPLE_SPACING) {
                                pts.add(p)
                            }
                        }
                        if (pts.size >= 3) {
                            val sm = ArrayList<TrackPoint>(pts.size)
                            sm.add(pts[0])
                            for (i in 1 until pts.size - 1) {
                                sm.add(
                                    TrackPoint(
                                        pts[i].t,
                                        (pts[i - 1].x + pts[i].x + pts[i + 1].x) / 3.0,
                                        pts[i].y,
                                    ),
                                )
                            }
                            sm.add(pts.last())
                            pts = sm
                        }
                        val result = ArrayList<TrackPoint>(pts.size)
                        result.add(TrackPoint(pts[0].t + offs, pts[0].x, pts[0].y))
                        var lastT = pts[0].t
                        for (i in 1 until pts.size) {
                            val p = pts[i]
                            if (p.t - lastT >= dragSampleMs / 1000.0) {
                                result.add(TrackPoint(p.t + offs, p.x, p.y))
                                lastT = p.t
                            }
                        }
                        result
                    } else {
                        listOf(TrackPoint(n.tSec + offs, n.x, 0.5))
                    }

                    for (p in sampled) {
                        val b = Quantizer.quantize(
                            clock.timeToBeat(p.t), subdivision, tolerance,
                        )
                        val pos = PositionMapper.mapX(p.x, xMode, regions)
                        val note = makeNote(NOTE_DRAG, b, b, pos, clock)
                        out.add(note.copy(gid = gid))
                    }
                }
            }
        }

        var sorted = out.sortedWith(compareBy({ it.startBeat }, { it.positionX }))
        // RPE 要求非 Hold 在前、Hold 在后；sortedBy 稳定，保持前一阶段的相对顺序。
        sorted = sorted.sortedBy { if (it.type == NOTE_HOLD) 1 else 0 }
        return sorted
    }
}
