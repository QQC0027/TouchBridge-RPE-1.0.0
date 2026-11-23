package com.touchbridge.rpe.domain.autochart

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.export.InfoFiles
import com.touchbridge.rpe.export.PezPackager
import java.io.File
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PcmDecoderInstrumentedTest {
    private val cacheDir get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun actualDecodeMatchesPcmAnalysisAtCommonRatesAndChannelCounts() = runBlocking {
        for (rate in listOf(22050, 44100, 48000)) {
            for (channels in listOf(1, 2)) {
                withAudio(rate, channels) { audio ->
                    val samples = FloatArray(AutoChartConfig.SAMPLE_RATE * 4)
                    var size = 0
                    var storage: FloatArray? = null
                    val decoded = PcmDecoder.decode(audio) { block, count ->
                        storage?.let { assertSame(it, block) }
                        storage = block
                        assertTrue(count in 1..8192)
                        block.copyInto(samples, size, 0, count)
                        size += count
                    }
                    assertEquals(size.toLong(), decoded)
                    assertTrue(decoded in (AutoChartConfig.SAMPLE_RATE * 2L)..samples.size.toLong())
                    val expected = AudioAnalyzer.analyzePcm(samples.copyOf(size))
                    val progress = mutableListOf<Int>()
                    val actual = AudioAnalyzer.analyze(audio, { value, _ -> progress += value })
                    assertEquals(expected.durationSec, actual.durationSec, 0.0)
                    assertEquals(expected.bpm, actual.bpm, 1e-9)
                    assertArrayEquals(expected.onsetNorm, actual.onsetNorm, 1e-9)
                    assertArrayEquals(expected.rmsNorm, actual.rmsNorm, 1e-9)
                    for (band in expected.bands.keys) assertArrayEquals(expected.bands.getValue(band), actual.bands.getValue(band), 1e-9)
                    assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
                }
            }
        }
    }

    @Test
    fun callbackFailuresAndCancellationAllowAnotherDecode() = runBlocking {
        withAudio(44100, 2) { audio ->
            val consumerError = IllegalStateException("consumer")
            val thrown = expectFailure { PcmDecoder.decode(audio) { _, _ -> throw consumerError } }
            assertSame(consumerError, thrown)
            assertTrue(PcmDecoder.decode(audio) { _, _ -> } > 0)

            val cancellation = CancellationException("cancel in consumer")
            assertSame(cancellation, expectFailure { PcmDecoder.decode(audio) { _, _ -> throw cancellation } })
            assertTrue(PcmDecoder.decode(audio) { _, _ -> } > 0)

            var callbacks = 0
            val total = PcmDecoder.decode(audio) { _, _ -> callbacks++ }
            assertTrue(total % 8192 != 0L)
            var current = 0
            val tailError = IllegalArgumentException("tail")
            assertSame(tailError, expectFailure {
                PcmDecoder.decode(audio) { _, _ -> if (++current == callbacks) throw tailError }
            })
            for (failAt in listOf(4, 26)) {
                val progressError = IllegalStateException("progress $failAt")
                assertSame(progressError, expectFailure {
                    PcmDecoder.decode(audio, { percent, _ -> if (percent >= failAt) throw progressError }) { _, _ -> }
                })
                assertTrue(PcmDecoder.decode(audio) { _, _ -> } > 0)
            }
            supervisorScope {
                val job = async(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    PcmDecoder.decode(audio) { _, _ -> context.cancel(CancellationException("cancel scope")) }
                }
                val actual = expectFailure { job.await() }
                assertTrue(actual is CancellationException)
                assertTrue(job.isCancelled)
            }
            assertTrue(PcmDecoder.decode(audio) { _, _ -> } > 0)
        }
    }

    @Test
    fun invalidSourceDoesNotReportSuccessAndCanBeFollowedByValidDecode() = runBlocking {
        val invalid = File.createTempFile("invalid-audio-", ".mp4", cacheDir)
        try {
            invalid.writeText("not an audio track")
            var completed = false
            expectFailure { PcmDecoder.decode(invalid, { percent, _ -> if (percent == 26) completed = true }) { _, _ -> } }
            assertFalse(completed)
        } finally {
            invalid.delete()
        }
        withAudio(22050, 1) { audio -> assertTrue(PcmDecoder.decode(audio) { _, _ -> } > 0) }
    }

    @Test
    fun longAudioCanGenerateAndExportWithinMeasuredDeviceHeap() = runBlocking {
        assumeTrue("Enable with audioStress=true instrumentation argument", InstrumentationRegistry.getArguments().getString("audioStress") == "true")
        val runtime = Runtime.getRuntime()
        assertTrue("A <=256MiB heap device is required", runtime.maxMemory() <= 256L * 1024 * 1024)
        val file = File.createTempFile("long-audio-", ".m4a", cacheDir)
        val pez = File.createTempFile("long-chart-", ".pez", cacheDir)
        val start = SystemClock.elapsedRealtime()
        var sampledHeap = runtime.totalMemory() - runtime.freeMemory()
        var sampledNative = Debug.getNativeHeapAllocatedSize()
        try {
            withContext(Dispatchers.Default) {
                encodeAudio(file, 22050, 1, AutoChartConfig.MAX_AUDIO_SAMPLES - 8192)
                val result = AutoChartEngine.generate(file, ChartMeta(), AutoChartOptions()) { _, _ ->
                    sampledHeap = maxOf(sampledHeap, runtime.totalMemory() - runtime.freeMemory())
                    sampledNative = maxOf(sampledNative, Debug.getNativeHeapAllocatedSize())
                }
                assertTrue(result.analysis.durationSec in 1799.0..1800.0)
                val chart = result.charts.first()
                assertTrue(chart.noteCount > 0)
                PezPackager().build(pez, chart.rpe,
                    InfoFiles.PezMeta("long-audio", chart.level, "", "test", ".m4a", ".png", result.analysis.durationSec),
                    file, null, "long_audio_test",
                )
                ZipFile(pez).use { zip ->
                    assertTrue(zip.getEntry("long_audio_test.json").size > 0)
                    assertEquals(file.length(), zip.getEntry("long_audio_test.m4a").size)
                }
            }
            println("deviceAudioStress heapMax=${runtime.maxMemory()} sampledHeap=$sampledHeap sampledNative=$sampledNative elapsedMs=${SystemClock.elapsedRealtime() - start}")
        } finally {
            pez.delete()
            file.delete()
        }
    }

    private suspend fun withAudio(rate: Int, channels: Int, block: suspend (File) -> Unit) {
        val file = File.createTempFile("decoder-", ".m4a", cacheDir)
        try {
            encodeAudio(file, rate, channels, rate * 3L)
            block(file)
        } finally {
            file.delete()
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: Exception) {
            return error
        }
        throw AssertionError("Expected decode failure")
    }

    private suspend fun encodeAudio(file: File, rate: Int, channels: Int, frames: Long) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var started = false
        var muxerStarted = false
        var failure: Throwable? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000 * channels)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val info = MediaCodec.BufferInfo()
            var track = -1
            var position = 0L
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + 300_000
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "AAC test fixture encoding timed out" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val count = minOf((buffer.remaining() / (channels * 2)).toLong(), frames - position).toInt()
                        repeat(count) { offset ->
                            val sample = position + offset
                            val pulse = if (sample % (rate / 2) < rate / 100) .7 else 0.0
                            val value = ((pulse + .05 * sin(2 * PI * 440 * sample / rate)) * 32767).toInt().toShort()
                            repeat(channels) { buffer.putShort(value) }
                        }
                        val timestamp = position * 1_000_000 / rate
                        val flags = if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, count * channels * 2, timestamp, flags)
                        position += count
                        inputDone = count == 0
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = writer.addTrack(codec.outputFormat)
                        writer.start()
                        muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted)
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                writer.writeSampleData(track, buffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            releaseResources(failure,
                { if (started) codec.stop() }, { codec.release() },
                { if (muxerStarted) muxer?.stop() }, { muxer?.release() },
            )
        }
    }
}
