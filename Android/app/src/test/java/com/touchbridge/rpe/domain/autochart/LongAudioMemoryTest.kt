package com.touchbridge.rpe.domain.autochart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LongAudioMemoryTest {
    @Test
    fun processesTwentyNineMinutesUnder256MiB() = process(29 * 60L * AutoChartConfig.SAMPLE_RATE)

    @Test
    fun acceptsExactlyThirtyMinutesAndRejectsNextSampleUnder256MiB() = process(AutoChartConfig.MAX_AUDIO_SAMPLES)

    private fun process(total: Long) {
        assertEquals("Run with -PaudioStress=true", "true", System.getProperty("audioStress"))
        val maximum = Runtime.getRuntime().maxMemory()
        assertTrue("Test worker must be capped at 256MiB: $maximum", maximum <= 256L * 1024 * 1024)
        val runtime = Runtime.getRuntime()
        var sampledPeak = runtime.totalMemory() - runtime.freeMemory()
        val start = System.nanoTime()
        val features = StreamingAudioFeatures()
        val output = PcmChunkBuffer { samples, size ->
            features.append(samples, length = size)
            sampledPeak = maxOf(sampledPeak, runtime.totalMemory() - runtime.freeMemory())
        }
        val pattern = FloatArray(1024) { (.25 * sin(2 * PI * it / 128)).toFloat() }
        var emitted = 0L
        while (emitted < total) {
            output.append(if (emitted % 11025 < 64) .9f else pattern[(emitted % pattern.size).toInt()])
            emitted++
        }
        if (total == AutoChartConfig.MAX_AUDIO_SAMPLES) {
            assertThrows(IllegalArgumentException::class.java) { output.append(0f) }
        }
        output.finish()
        assertEquals(total, output.sampleCount)
        assertEquals(total, features.sampleCount)
        if (total == AutoChartConfig.MAX_AUDIO_SAMPLES) {
            assertThrows(IllegalArgumentException::class.java) { features.append(floatArrayOf(0f)) }
        }
        val result = features.finish()
        val expectedFrames = ((total + 511) / 512).toInt()
        assertEquals(expectedFrames, result.onset.size)
        assertEquals(expectedFrames, result.rms.size)
        assertTrue(result.bands.values.all { it.size == expectedFrames })
        assertTrue((listOf(result.onset, result.rms) + result.bands.values).all { values ->
            values.all { it.isFinite() && it in 0.0..1.0 }
        })
        sampledPeak = maxOf(sampledPeak, runtime.totalMemory() - runtime.freeMemory())
        println("audioStress samples=$total frames=$expectedFrames heapMax=$maximum sampledHeapPeak=$sampledPeak elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
    }
}
