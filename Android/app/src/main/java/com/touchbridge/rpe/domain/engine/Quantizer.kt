package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.Rounder
import kotlin.math.abs
import kotlin.math.max

/** 网格吸附 + 吸附容差，逐行对应 engine.py 的 quantize()。 */
object Quantizer {
    fun quantize(beat: Double, subdivision: Int, tolerance: Double): Double {
        val grid = 1.0 / max(1, subdivision)
        val nearest = Rounder.roundToDouble(beat / grid) * grid
        return if (abs(beat - nearest) <= tolerance * grid) nearest else beat
    }
}
