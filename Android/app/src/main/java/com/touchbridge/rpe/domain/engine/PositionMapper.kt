package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.Rounder

/** 把 0~1 的 x 或区域编号映射到 RPE positionX(-675~675)。 */
object PositionMapper {
    fun mapX(x: Double, xMode: String, regions: Int): Double {
        val x01: Double = if (xMode == "region") {
            val safeRegions = maxOf(1, regions)
            var idx = Rounder.roundToLong(x).toInt()
            idx = idx.coerceIn(0, safeRegions - 1)
            (idx + 0.5) / safeRegions
        } else {
            x.coerceIn(0.0, 1.0)
        }
        val pos = -675.0 + x01 * 1350.0
        return pos.coerceIn(-675.0, 675.0)
    }
}
