package com.touchbridge.rpe.domain.autochart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearResamplerTest {
    @Test
    fun sameRateKeepsEveryInputSampleExactlyOnce() {
        val input = floatArrayOf(.1f, -.2f, .3f, -.4f)
        val output = resample(input, AutoChartConfig.SAMPLE_RATE)
        assertEquals(input.size, output.size)
        input.indices.forEach { assertEquals(input[it], output[it], 0f) }
    }

    @Test
    fun upsamplingPreservesEndpointsAndInterpolatesBetweenSamples() {
        val output = resample(floatArrayOf(0f, 1f), AutoChartConfig.SAMPLE_RATE / 2)
        assertEquals(3, output.size)
        assertEquals(0f, output[0], 0f)
        assertEquals(.5f, output[1], 1e-6f)
        assertEquals(1f, output[2], 0f)
    }

    @Test
    fun downsamplingUsesCorrectSourceTimePositions() {
        val output = resample(floatArrayOf(0f, 1f, 0f, -1f, 0f), AutoChartConfig.SAMPLE_RATE * 2)
        assertEquals(3, output.size)
        assertEquals(0f, output[0], 0f)
        assertTrue(output.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun sourceChunksAndOutputBlocksKeepPhaseAndTail() {
        val input = FloatArray(17011) { ((it % 127) / 63.0 - 1).toFloat() }
        for (rate in listOf(11025, 22050, 44100, 48000)) {
            val expected = resample(input, rate)
            for (chunkSize in listOf(1, 137, 4096)) {
                val actual = FloatArray(expected.size)
                var written = 0
                val output = PcmChunkBuffer { samples, size ->
                    samples.copyInto(actual, written, 0, size)
                    written += size
                }
                val sampler = LinearResampler(rate)
                val emit: (Float) -> Unit = output::append
                for (offset in input.indices step chunkSize) {
                    for (i in offset until minOf(offset + chunkSize, input.size)) sampler.append(input[i], emit)
                }
                output.finish()
                assertEquals(expected.size, written)
                assertEquals(expected.size.toLong(), output.sampleCount)
                assertArrayEquals(expected, actual, 0f)
            }
        }
    }

    private fun resample(input: FloatArray, sourceRate: Int): FloatArray {
        val result = mutableListOf<Float>()
        val resampler = LinearResampler(sourceRate)
        input.forEach { resampler.append(it, result::add) }
        return result.toFloatArray()
    }
}
