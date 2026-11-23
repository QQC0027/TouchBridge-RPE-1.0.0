package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.Rounder
import kotlin.math.floor

/** 自动校准：根据音符相对整拍偏差的中位数估算 hit_offset_ms。 */
object Calibrator {
    fun estimate(notes: List<GestureNote>, clock: BeatClock): Double? {
        if (notes.isEmpty()) return null
        val bpm = clock.segments.first().bpm
        val beatDur = if (bpm > 0) 60.0 / bpm else 0.5
        val fracs = ArrayList<Double>(notes.size)
        for (n in notes) {
            val beat = clock.timeToBeat(n.tSec)
            var f = beat - floor(beat)
            if (f > 0.5) f -= 1.0
            fracs.add(f)
        }
        if (fracs.isEmpty()) return null
        fracs.sort()
        val bias = fracs[fracs.size / 2]
        return Rounder.round(-bias * beatDur * 1000.0, 1)
    }
}
