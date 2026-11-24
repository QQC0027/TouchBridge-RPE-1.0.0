package com.touchbridge.rpe.domain.autochart

import com.touchbridge.rpe.domain.model.GestureNote
import kotlin.math.abs
import kotlin.math.round

object SnapAligner {
    data class Result(val notes: List<GestureNote>, val moved: Int)

    fun align(
        notes: List<GestureNote>, analysis: AutoAnalysis, windowMs: Double = 120.0, strength: Double = 1.0,
    ): Result {
        require(windowMs in 0.0..1000.0 && strength in 0.0..1.0)
        if (windowMs == 0.0 || strength == 0.0 || analysis.points.isEmpty()) return Result(notes, 0)
        val points = analysis.points.sortedBy { it.timeSec }
        val window = windowMs / 1000
        val beatSec = 60 / analysis.bpm
        var moved = 0
        return Result(notes.map { note ->
            val time = note.tSec
            val grid = analysis.offsetSec + round((time - analysis.offsetSec) / beatSec) * beatSec
            var target = time
            var best = Double.POSITIVE_INFINITY
            fun consider(candidate: Double, probability: Double) {
                val distance = abs(candidate - time)
                if (distance > window) return
                val score = distance / (0.75 + 0.25 * probability)
                if (score < best || (score == best && candidate < target)) {
                    target = candidate
                    best = score
                }
            }
            if (grid >= 0) consider(grid, 0.5)
            var lo = 0
            var hi = points.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (points[mid].timeSec < time - window) lo = mid + 1 else hi = mid
            }
            while (lo < points.size && points[lo].timeSec <= time + window) {
                val point = points[lo++]
                consider(point.timeSec, point.probability)
            }
            val delta = (target - time) * strength
            if (abs(delta) <= 1e-9) note else {
                moved++
                note.copy(tSec = time + delta, path = note.path.map { it.copy(t = it.t + delta) })
            }
        }, moved)
    }
}
