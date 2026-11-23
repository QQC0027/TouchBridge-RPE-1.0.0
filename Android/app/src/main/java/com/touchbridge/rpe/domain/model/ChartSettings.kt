package com.touchbridge.rpe.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与 backend/config.json 的 settings 键同名同默认值，便于两版行为对齐。
 */
@Serializable
data class ChartSettings(
    val bpm: Double = 160.0,
    @SerialName("offset_ms") val offsetMs: Double = 0.0,
    @SerialName("beats_per_bar") val beatsPerBar: Int = 4,
    val subdivision: Int = 8,
    @SerialName("snap_tolerance") val snapTolerance: Double = 0.6,
    @SerialName("debounce_ms") val debounceMs: Double = 40.0,
    @SerialName("x_mode") val xMode: String = "coordinate",
    val regions: Int = 9,
    @SerialName("flick_speed") val flickSpeed: Double = 2.0,
    @SerialName("drag_sample_ms") val dragSampleMs: Double = 25.0,
    val deadZone: Double = 0.03,
    val holdMinTime: Double = 0.2,
    val flickWindow: Double = 0.2,
    val flickMaxDist: Double = 0.25,
    val segments: List<BpmSegment> = emptyList(),
    val dragMinSpeed: Double = 0.25,
    val dragMaxDur: Double = 1.5,
    @SerialName("hit_offset_ms") val hitOffsetMs: Double = 0.0,
) {
    fun quantizeSubdivision(): Int = maxOf(1, subdivision)
}
