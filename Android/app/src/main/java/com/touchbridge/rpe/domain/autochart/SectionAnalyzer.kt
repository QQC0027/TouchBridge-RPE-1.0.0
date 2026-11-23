package com.touchbridge.rpe.domain.autochart

import kotlin.math.ceil
import kotlin.math.max

object SectionAnalyzer {
    fun analyze(rms: DoubleArray, durationSec: Double, bpm: Double, hopLength: Int = AutoChartConfig.HOP_LENGTH): List<AutoSection> {
        if (durationSec <= 0) return emptyList()
        val safeBpm = bpm.takeIf { it > 0 } ?: 120.0
        val window = AutoChartConfig.SECTION_WINDOW * 60.0 / safeBpm
        val count = max(1, ceil(durationSec / window).toInt())
        val sorted = rms.sorted()
        val median = sorted.getOrNull(sorted.size / 2) ?: 0.0
        val nonSilent = sorted.filter { it > SILENCE_EPSILON }
        val baseline = if (median > SILENCE_EPSILON) median else nonSilent.getOrNull(nonSilent.size / 2) ?: 0.0
        return List(count) { index ->
            val start = index * window
            val end = minOf((index + 1) * window, durationSec)
            val firstFrame = (start * AutoChartConfig.SAMPLE_RATE / hopLength).toInt().coerceIn(0, rms.size)
            val lastFrame = (end * AutoChartConfig.SAMPLE_RATE / hopLength).toInt().coerceIn(firstFrame, rms.size)
            val average = if (lastFrame > firstFrame) rms.slice(firstFrame until lastFrame).average() else 0.0
            val label: String
            val density: Double
            when {
                average <= SILENCE_EPSILON || baseline <= SILENCE_EPSILON -> { label = "intro/break"; density = AutoChartConfig.DENSITY_LOW }
                average < baseline * .6 -> { label = "intro/break"; density = AutoChartConfig.DENSITY_LOW }
                average < baseline * 1.3 -> { label = "verse"; density = AutoChartConfig.DENSITY_MID }
                else -> { label = "chorus"; density = AutoChartConfig.DENSITY_HIGH }
            }
            AutoSection(start, end, label, density)
        }
    }

    private const val SILENCE_EPSILON = 1e-8
}
