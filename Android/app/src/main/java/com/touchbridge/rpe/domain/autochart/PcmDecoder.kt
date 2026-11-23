package com.touchbridge.rpe.domain.autochart

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PcmDecoder {
    suspend fun decode(
        file: File,
        progress: (Int, String) -> Unit = { _, _ -> },
        consume: (FloatArray, Int) -> Unit,
    ): Long {
        val context = currentCoroutineContext()
        context.ensureActive()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var failure: Throwable? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = index; format = candidate; break }
            }
            require(track >= 0 && format != null) { "找不到可解码的音轨" }
            extractor.selectTrack(track)
            val sourceRate = format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            codecStarted = true
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val output = PcmChunkBuffer(consume = { samples, size ->
                context.ensureActive()
                consume(samples, size)
            })
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val resampler = LinearResampler(sourceRate)
            val emitSample: (Float) -> Unit = output::append
            var framesRead = 0L
            var outputRate = sourceRate
            var outputChannels = channels
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var lastPercent = -1
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decoded = codec.outputFormat
                        outputRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(outputRate > 0 && outputChannels > 0) { "音频采样格式无效" }
                        resampler.setSourceRate(outputRate)
                        require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT || pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) { "不支持该音频 PCM 格式" }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        var outputFailure: Throwable? = null
                        try {
                            val buffer = codec.getOutputBuffer(outputIndex)!!
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = info.size
                            val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val sampleCount = bytes / bytesPerSample
                            val frameCount = sampleCount / outputChannels
                            val frameStart = info.offset
                            buffer.position(frameStart)
                            buffer.limit(frameStart + bytes)
                            for (frame in 0 until frameCount) {
                                if ((frame and 1023) == 0) context.ensureActive()
                                var sample = 0.0
                                repeat(outputChannels) {
                                    sample += if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.toDouble() else buffer.short.toDouble() / 32768.0
                                }
                                resampler.append((sample / outputChannels).toFloat(), emitSample)
                                framesRead++
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } catch (error: Throwable) {
                            outputFailure = error
                            throw error
                        } finally {
                            releaseResources(outputFailure, { codec.releaseOutputBuffer(outputIndex, false) })
                        }
                        if (duration > 0) {
                            val percent = 4 + (20.0 * framesRead * 1_000_000 / (duration * outputRate)).toInt().coerceIn(0, 20)
                            if (percent != lastPercent) { lastPercent = percent; progress(percent, "正在解码音频") }
                        }
                    }
                }
            }
            context.ensureActive()
            output.finish()
            context.ensureActive()
            progress(26, "音频解码完成")
            return output.sampleCount
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            releaseResources(failure,
                { if (codecStarted) codec?.stop() },
                { codec?.release() },
                { extractor.release() },
            )
        }
    }
}

internal fun releaseResources(failure: Throwable?, vararg releases: () -> Unit) {
    var primary = failure
    for (release in releases) {
        try {
            release()
        } catch (error: Throwable) {
            val original = primary
            if (original == null) primary = error else if (original !== error) original.addSuppressed(error)
        }
    }
    if (failure == null) primary?.let { throw it }
}

internal class PcmChunkBuffer(
    private val consume: (FloatArray, Int) -> Unit,
) {
    private val samples = FloatArray(8192)
    private var size = 0
    private var finished = false
    var sampleCount = 0L
        private set

    fun append(value: Float) {
        check(!finished)
        require(sampleCount < AutoChartConfig.MAX_AUDIO_SAMPLES) { "音频超过 30 分钟的分析上限" }
        samples[size++] = value
        sampleCount++
        if (size == samples.size) flush()
    }

    fun finish() {
        check(!finished)
        flush()
        finished = true
    }

    private fun flush() {
        if (size == 0) return
        consume(samples, size)
        size = 0
    }
}

internal class LinearResampler(sourceRate: Int) {
    private var sourceRate = sourceRate
    private var step = sourceRate.toDouble() / AutoChartConfig.SAMPLE_RATE
    private var inputIndex = 0L
    private var outputIndex = 0L
    private var previous = 0f
    private var filtered = 0f
    private var filterInitialized = false

    init {
        require(sourceRate > 0) { "音频采样率无效" }
    }

    fun setSourceRate(rate: Int) {
        require(rate > 0) { "音频采样率无效" }
        if (inputIndex > 0L) require(rate == sourceRate) { "解码过程中音频采样率发生变化" }
        sourceRate = rate
        step = rate.toDouble() / AutoChartConfig.SAMPLE_RATE
    }

    fun append(sample: Float, emit: (Float) -> Unit) {
        val prepared = antiAlias(sample)
        if (inputIndex == 0L) {
            emit(prepared)
            outputIndex++
        } else {
            while (outputIndex * step <= inputIndex) {
                val fraction = (outputIndex * step - (inputIndex - 1)).coerceIn(0.0, 1.0)
                val value = when {
                    fraction <= 0.0 -> previous
                    fraction >= 1.0 -> prepared
                    else -> (previous + (prepared - previous) * fraction).coerceIn(-1.0, 1.0).toFloat()
                }
                emit(value)
                outputIndex++
            }
        }
        previous = prepared
        inputIndex++
    }

    private fun antiAlias(sample: Float): Float {
        if (sourceRate <= AutoChartConfig.SAMPLE_RATE) return sample
        if (!filterInitialized) {
            filtered = sample
            filterInitialized = true
            return sample
        }
        val cutoffHz = AutoChartConfig.SAMPLE_RATE * 0.45
        val dt = 1.0 / sourceRate
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        val alpha = (dt / (rc + dt)).toFloat()
        filtered += alpha * (sample - filtered)
        return filtered
    }
}
