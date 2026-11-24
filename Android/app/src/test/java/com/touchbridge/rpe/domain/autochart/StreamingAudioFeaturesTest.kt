package com.touchbridge.rpe.domain.autochart

import java.util.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class StreamingAudioFeaturesTest {
    @Test
    fun matchesOriginalBatchAlgorithmAcrossAllBlockSizes() {
        val random = Random(19)
        val pcm = FloatArray(22051) { index ->
            ((random.nextDouble() - .5) * if (index < 15000) .05 else 1.8).toFloat()
        }
        val expected = batchReference(pcm)
        for (size in listOf(1, 113, 512, 8192, pcm.size)) assertFeatures(expected, streamed(pcm) { size })
        val blocks = Random(42)
        assertFeatures(expected, streamed(pcm) { blocks.nextInt(3000) + 1 })
    }

    @Test
    fun preservesCenteredWindowsAndEofPadding() {
        for (size in listOf(1, 511, 512, 513, 1023, 1024, 1025, 2047, 2048, 2049, 8191, 8192, 8193)) {
            val pcm = FloatArray(size)
            pcm[0] = .4f
            pcm[size / 2] = -.8f
            pcm[size - 1] = 1f
            val actual = streamed(pcm) { 137 }
            assertEquals((size + 511) / 512, actual.onset.size)
            assertFeatures(batchReference(pcm), actual)
        }
    }

    @Test
    fun handlesSilenceAndGlobalNormalization() {
        val silent = streamed(FloatArray(4097)) { 37 }
        assertTrue((listOf(silent.onset, silent.rms) + silent.bands.values).all { values -> values.all { it == 0.0 } })
        val pcm = FloatArray(8193)
        pcm[512] = .01f
        pcm[7000] = 1f
        val actual = streamed(pcm) { 512 }
        assertFeatures(batchReference(pcm), actual)
        assertEquals(1.0, actual.onset.maxOrNull()!!, 0.0)
        assertTrue(actual.onset[1] < .5)
    }

    @Test
    fun doesNotRetainBorrowedInputAndUsesOnlyValidSlice() {
        val pcm = FloatArray(10001) { sin(it * .1).toFloat() }
        val expected = batchReference(pcm)
        val stream = StreamingAudioFeatures()
        val borrowed = FloatArray(263)
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(257, pcm.size - offset)
            borrowed.fill(Float.NaN)
            pcm.copyInto(borrowed, 3, offset, offset + length)
            stream.append(borrowed, 3, length)
            borrowed.fill(42f)
            offset += length
        }
        assertEquals(pcm.size.toLong(), stream.sampleCount)
        assertFeatures(expected, stream.finish())
        assertThrows(IllegalStateException::class.java) { stream.append(floatArrayOf(0f)) }
        assertThrows(IllegalStateException::class.java) { stream.finish() }
    }

    @Test
    fun propagatesCancellationDuringInputAndTailProcessing() {
        val cancelled = CancellationException("cancelled")
        var active = true
        val stream = StreamingAudioFeatures { if (!active) throw cancelled }
        stream.append(FloatArray(8192))
        active = false
        assertSame(cancelled, assertThrows(CancellationException::class.java) { stream.append(FloatArray(1)) })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { stream.finish() })
        assertThrows(IllegalArgumentException::class.java) { StreamingAudioFeatures().finish() }
    }

    @Test
    fun checksCancellationWhileProcessingOneLargeBlock() {
        val cancelled = CancellationException("cancel during frames")
        var checks = 0
        val stream = StreamingAudioFeatures { if (++checks == 3) throw cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { stream.append(FloatArray(100000)) })
        assertTrue(stream.sampleCount in 1024L until 100000L)
        assertEquals(3, checks)
    }

    @Test
    fun chunkBufferReusesBoundedStorageAndFlushesExactTail() {
        var previous: FloatArray? = null
        val lengths = mutableListOf<Int>()
        var read = 0
        val output = PcmChunkBuffer { samples, length ->
            previous?.let { assertSame(it, samples) }
            previous = samples
            assertEquals(8192, samples.size)
            lengths += length
            repeat(length) { assertEquals((read++ % 100).toFloat(), samples[it], 0f) }
        }
        repeat(8192 * 2 + 17) { output.append((it % 100).toFloat()) }
        output.finish()
        assertEquals(listOf(8192, 8192, 17), lengths)
        assertEquals(16401L, output.sampleCount)
        assertEquals(16401, read)
        assertThrows(IllegalStateException::class.java) { output.append(0f) }
    }

    @Test
    fun chunkConsumerFailurePropagatesWithoutSuccessfulCompletion() {
        val expected = IllegalStateException("consumer failed")
        val output = PcmChunkBuffer { _, _ -> throw expected }
        repeat(8191) { output.append(0f) }
        assertSame(expected, assertThrows(IllegalStateException::class.java) { output.append(0f) })
    }

    @Test
    fun releasesAllResourcesAndKeepsOriginalFailure() {
        val primary = CancellationException("cancelled")
        val errors = listOf(IllegalStateException("stop"), IllegalArgumentException("release"))
        val released = mutableListOf<Int>()
        releaseResources(primary,
            { released += 1; throw errors[0] },
            { released += 2; throw errors[1] },
            { released += 3 },
        )
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(errors, primary.suppressed.toList())
        val actual = assertThrows(IllegalStateException::class.java) {
            releaseResources(null, { throw errors[0] }, { released += 4 })
        }
        assertSame(errors[0], actual)
        assertEquals(listOf(1, 2, 3, 4), released)
    }

    private fun streamed(pcm: FloatArray, nextBlock: () -> Int): AudioFeatures {
        val stream = StreamingAudioFeatures()
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(nextBlock(), pcm.size - offset)
            stream.append(pcm, offset, length)
            offset += length
        }
        return stream.finish()
    }

    private fun assertFeatures(expected: AudioFeatures, actual: AudioFeatures) {
        assertArrayEquals(expected.onset, actual.onset, 1e-12)
        assertArrayEquals(expected.rms, actual.rms, 1e-12)
        for (band in listOf("low", "mid", "high")) assertArrayEquals(expected.bands.getValue(band), actual.bands.getValue(band), 1e-12)
    }

    companion object {
        internal fun batchReference(pcm: FloatArray): AudioFeatures {
            val size = 2048
            val frames = (pcm.size + 511) / 512
            val hann = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
            val previous = DoubleArray(size / 2 + 1)
            val onset = DoubleArray(frames)
            val rms = DoubleArray(frames)
            val low = DoubleArray(frames)
            val mid = DoubleArray(frames)
            val high = DoubleArray(frames)
            val real = DoubleArray(size)
            val imag = DoubleArray(size)
            for (frame in 0 until frames) {
                val offset = frame * 512 - size / 2
                var sum = 0.0
                imag.fill(0.0)
                for (i in 0 until size) {
                    val value = pcm.getOrElse(offset + i) { 0f }.toDouble()
                    sum += value * value
                    real[i] = value * hann[i]
                }
                rms[frame] = sqrt(sum / size)
                batchFft(real, imag)
                var flux = 0.0
                for (bin in 0..size / 2) {
                    val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                    val frequency = bin.toDouble() * 22050 / size
                    if (frequency in 20.0..250.0) low[frame] += magnitude
                    else if (frequency in 250.0..2000.0) mid[frame] += magnitude
                    else if (frequency in 2000.0..11025.0) high[frame] += magnitude
                    flux += max(0.0, magnitude - previous[bin])
                    previous[bin] = magnitude
                }
                onset[frame] = flux
            }
            fun normalize(values: DoubleArray, log: Boolean = false): DoubleArray {
                val maximum = values.maxOrNull() ?: 0.0
                return DoubleArray(values.size) { index ->
                    val value = if (log) ln(1 + values[index]) else values[index]
                    val top = if (log) ln(1 + maximum) else maximum
                    if (top > 1e-12) value / top else 0.0
                }
            }
            return AudioFeatures(normalize(onset, true), normalize(rms), mapOf("low" to normalize(low, true), "mid" to normalize(mid, true), "high" to normalize(high, true)))
        }

        private fun batchFft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val r = real[i]; real[i] = real[j]; real[j] = r
                    val v = imag[i]; imag[i] = imag[j]; imag[j] = v
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val rootR = cos(angle)
                val rootI = sin(angle)
                for (start in 0 until n step length) {
                    var wr = 1.0
                    var wi = 0.0
                    for (k in 0 until length / 2) {
                        val even = start + k
                        val odd = even + length / 2
                        val tr = wr * real[odd] - wi * imag[odd]
                        val ti = wr * imag[odd] + wi * real[odd]
                        real[odd] = real[even] - tr
                        imag[odd] = imag[even] - ti
                        real[even] += tr
                        imag[even] += ti
                        val nextR = wr * rootR - wi * rootI
                        wi = wr * rootI + wi * rootR
                        wr = nextR
                    }
                }
                length = length shl 1
            }
        }
    }
}
