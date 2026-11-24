package com.touchbridge.rpe.domain.autochart

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.roundToInt

class AutoAnalysisTest {
    @Test
    fun detectsRegularPulseTrainAndSections() = runBlocking {
        val bpm = 120.0
        val sampleRate = AutoChartConfig.SAMPLE_RATE
        val pcm = FloatArray(sampleRate * 16)
        for (beat in 0 until 32) {
            val start = (beat * 60.0 / bpm * sampleRate).toInt()
            for (i in 0 until (sampleRate * .08).toInt()) {
                val t = i.toDouble() / sampleRate
                pcm[start + i] = (0.9 * exp(-45 * t) * sin(2 * PI * 660 * t)).toFloat()
            }
        }
        val expected = StreamingAudioFeaturesTest.batchReference(pcm)
        val analysis = AudioAnalyzer.analyzePcm(pcm)
        assertArrayEquals(expected.onset, analysis.onsetNorm, 1e-12)
        assertArrayEquals(expected.rms, analysis.rmsNorm, 1e-12)
        for (band in expected.bands.keys) assertArrayEquals(expected.bands.getValue(band), analysis.bands.getValue(band), 1e-12)
        assertEquals(120.0, analysis.bpm, 3.0)
        assertTrue(analysis.points.size >= 24)
        assertEquals(16.0, analysis.durationSec, 1e-9)
        assertTrue(analysis.sections.isNotEmpty())
        assertTrue(analysis.bands.values.all { it.size == analysis.onsetNorm.size })
        val gaps = analysis.points.zipWithNext { a, b -> b.timeSec - a.timeSec }
        assertTrue(gaps.all { it > .1 })
    }

    @Test
    fun rejectsEmptyPcmAndUsesActualDurationForShortInput() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioAnalyzer.analyzePcm(floatArrayOf()) } }
        for (size in listOf(1, 511, 512, 513)) {
            val result = AudioAnalyzer.analyzePcm(FloatArray(size))
            assertEquals(size.toDouble() / AutoChartConfig.SAMPLE_RATE, result.durationSec, 0.0)
            assertEquals((size + 511) / 512, result.onsetNorm.size)
        }
    }

    @Test
    fun preservesImportedPointsContractWhileComputingFeatures() = runBlocking {
        val pcm = FloatArray(22051) { if (it == 8100) 1f else 0f }
        val actualDuration = pcm.size.toDouble() / AutoChartConfig.SAMPLE_RATE
        val tick = (.8 * 123.0 / 60.0 * 480).roundToInt()
        val imported = AutoAnalysis(123.0, .25, actualDuration, listOf(AutoPoint(.8, tick / 480.0, tick, .9)), listOf(AutoTempo(0, 123.0)))
        val result = AudioAnalyzer.analyzePcm(pcm, pointsOverride = imported)
        assertEquals(imported.points, result.points)
        assertEquals(imported.tempoEvents, result.tempoEvents)
        assertEquals(imported.bpm, result.bpm, 0.0)
        assertEquals(imported.offsetSec, result.offsetSec, 0.0)
        assertEquals(imported.durationSec, result.durationSec, 0.0)
        assertArrayEquals(StreamingAudioFeaturesTest.batchReference(pcm).onset, result.onsetNorm, 1e-12)
        assertTrue(result.sections.isNotEmpty())
    }
}
