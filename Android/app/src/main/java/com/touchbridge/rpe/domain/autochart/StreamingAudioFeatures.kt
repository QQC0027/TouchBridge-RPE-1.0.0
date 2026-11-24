package com.touchbridge.rpe.domain.autochart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AudioFeatures(
    val onset: DoubleArray,
    val rms: DoubleArray,
    val bands: Map<String, DoubleArray>,
)

internal class StreamingAudioFeatures(private val checkActive: () -> Unit = {}) {
    private val pcm = FloatArray(FFT_SIZE)
    private val hann = DoubleArray(FFT_SIZE) { .5 - .5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
    private val previous = DoubleArray(FFT_SIZE / 2 + 1)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val onset = FrameValues()
    private val rms = FrameValues()
    private val low = FrameValues()
    private val mid = FrameValues()
    private val high = FrameValues()
    private var frame = 0
    private var finished = false
    var sampleCount = 0L
        private set

    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!finished) { "频谱特征已结束" }
        require(offset >= 0 && length >= 0 && offset <= samples.size - length)
        require(length.toLong() <= AutoChartConfig.MAX_AUDIO_SAMPLES - sampleCount) { "音频超过 30 分钟的分析上限" }
        checkActive()
        for (index in offset until offset + length) {
            pcm[(sampleCount % FFT_SIZE).toInt()] = samples[index]
            sampleCount++
            if (sampleCount >= frame.toLong() * AutoChartConfig.HOP_LENGTH + FFT_SIZE / 2) {
                processFrame()
            }
        }
    }

    fun finish(): AudioFeatures {
        check(!finished) { "频谱特征已结束" }
        require(sampleCount > 0) { "音频没有可分析的采样" }
        checkActive()
        while (frame.toLong() * AutoChartConfig.HOP_LENGTH < sampleCount) processFrame()
        finished = true
        return AudioFeatures(
            onset.normalized(true), rms.normalized(false),
            mapOf("low" to low.normalized(true), "mid" to mid.normalized(true), "high" to high.normalized(true)),
        )
    }

    private fun processFrame() {
        if ((frame and 127) == 0) checkActive()
        val offset = frame.toLong() * AutoChartConfig.HOP_LENGTH - FFT_SIZE / 2
        var sum = 0.0
        im.fill(0.0)
        for (i in 0 until FFT_SIZE) {
            val index = offset + i
            val value = if (index < 0 || index >= sampleCount) 0.0 else pcm[(index % FFT_SIZE).toInt()].toDouble()
            sum += value * value
            re[i] = value * hann[i]
        }
        rms.add(sqrt(sum / FFT_SIZE))
        fft(re, im)
        var flux = 0.0
        var lo = 0.0
        var mi = 0.0
        var hi = 0.0
        for (bin in 0 until re.size / 2 + 1) {
            val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val frequency = bin.toDouble() * AutoChartConfig.SAMPLE_RATE / FFT_SIZE
            if (frequency in 20.0..250.0) lo += magnitude
            else if (frequency in 250.0..2000.0) mi += magnitude
            else if (frequency in 2000.0..11025.0) hi += magnitude
            flux += max(0.0, magnitude - previous[bin])
            previous[bin] = magnitude
        }
        onset.add(flux)
        low.add(lo)
        mid.add(mi)
        high.add(hi)
        frame++
    }

    private class FrameValues {
        private var values = DoubleArray(1024)
        private var size = 0

        fun add(value: Double) {
            check(size < MAX_FRAMES)
            if (size == values.size) values = values.copyOf(minOf(MAX_FRAMES, values.size * 2))
            values[size++] = value
        }

        fun normalized(logScale: Boolean): DoubleArray {
            var maximum = 0.0
            for (i in 0 until size) maximum = max(maximum, values[i])
            val top = if (logScale) ln(1 + maximum) else maximum
            for (i in 0 until size) {
                val value = if (logScale) ln(1 + values[i]) else values[i]
                values[i] = if (top > 1e-12) value / top else 0.0
            }
            return if (size == values.size) values else values.copyOf(size)
        }
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val MAX_FRAMES = ((AutoChartConfig.MAX_AUDIO_SAMPLES + AutoChartConfig.HOP_LENGTH - 1) / AutoChartConfig.HOP_LENGTH).toInt()

        private fun fft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val v = imag[i]; imag[i] = imag[j]; imag[j] = v }
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
