package com.touchbridge.rpe.domain.autochart

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


data class AutoPoint(val timeSec: Double, val beat: Double, val beatTick: Int, val probability: Double, val snapStatus: Int = 1)
data class AutoTempo(val beatTick: Int, val bpm: Double)
data class AutoSection(val startTime: Double, val endTime: Double, val label: String, val density: Double)
data class AutoAnalysis(
    val bpm: Double,
    val offsetSec: Double,
    val durationSec: Double,
    val points: List<AutoPoint>,
    val tempoEvents: List<AutoTempo>,
    val ticksPerBeat: Int = 480,
    val onsetNorm: DoubleArray = doubleArrayOf(),
    val rmsNorm: DoubleArray = doubleArrayOf(),
    val bands: Map<String, DoubleArray> = emptyMap(),
    val sections: List<AutoSection> = emptyList(),
    val sampleRate: Int = AutoChartConfig.SAMPLE_RATE,
    val sr: Int = sampleRate,
    val hopLength: Int = AutoChartConfig.HOP_LENGTH,
)

object AudioAnalyzer {
    suspend fun analyze(file: File, progress: (Int, String) -> Unit = { _, _ -> }, pointsOverride: AutoAnalysis? = null): AutoAnalysis = withContext(Dispatchers.Default) {
        require(file.isFile) { "音频文件不存在" }
        val context = currentCoroutineContext()
        val extractor = StreamingAudioFeatures { context.ensureActive() }
        val sampleCount = PcmDecoder.decode(file, progress) { samples, size -> extractor.append(samples, length = size) }
        progress(28, "正在计算频谱特征")
        analyzeFeatures(extractor.finish(), sampleCount, progress, pointsOverride)
    }

    internal suspend fun analyzePcm(pcm: FloatArray, progress: (Int, String) -> Unit = { _, _ -> }, pointsOverride: AutoAnalysis? = null): AutoAnalysis {
        require(pcm.isNotEmpty()) { "音频没有可分析的采样" }
        require(pcm.size.toLong() <= AutoChartConfig.MAX_AUDIO_SAMPLES) { "音频超过 30 分钟的分析上限" }
        progress(28, "正在计算频谱特征")
        val context = currentCoroutineContext()
        val extractor = StreamingAudioFeatures { context.ensureActive() }
        for (offset in pcm.indices step 8192) extractor.append(pcm, offset, minOf(8192, pcm.size - offset))
        return analyzeFeatures(extractor.finish(), extractor.sampleCount, progress, pointsOverride)
    }

    private suspend fun analyzeFeatures(features: AudioFeatures, sampleCount: Long, progress: (Int, String) -> Unit, pointsOverride: AutoAnalysis?): AutoAnalysis {
        currentCoroutineContext().ensureActive()
        val durationSec = sampleCount.toDouble() / AutoChartConfig.SAMPLE_RATE
        if (pointsOverride != null) {
            validateImportedTimeline(pointsOverride, durationSec)
            progress(60, "已读取 ${pointsOverride.points.size} 个踩音点")
            return pointsOverride.copy(
                durationSec = durationSec,
                onsetNorm = features.onset, rmsNorm = features.rms, bands = features.bands,
                sections = SectionAnalyzer.analyze(features.rms, durationSec, pointsOverride.bpm),
            )
        }
        progress(48, "正在检测 BPM 与拍点")
        val bpm = estimateBpm(features.onset)
        val beatTimes = trackBeats(features.onset, bpm)
        val offset = beatTimes.firstOrNull() ?: 0.0
        val peakFrames = peakFrames(features.onset)
        val points = if (peakFrames.isNotEmpty()) peakFrames.map { frame ->
            val time = frame.toDouble() * AutoChartConfig.HOP_LENGTH / AutoChartConfig.SAMPLE_RATE
            val probability = features.onset[frame].coerceIn(.05, 1.0)
            val tick = (time * bpm / 60 * 480).let(::roundToIntSafe)
            AutoPoint(time, tick / 480.0, tick, probability)
        } else beatTimes.map { time ->
            val frame = (time * AutoChartConfig.SAMPLE_RATE / AutoChartConfig.HOP_LENGTH).roundToInt()
                .coerceIn(0, features.onset.lastIndex.coerceAtLeast(0))
            val probability = features.onset.getOrElse(frame) { .3 }.coerceIn(.05, 1.0)
            val tick = (time * bpm / 60 * 480).let(::roundToIntSafe)
            AutoPoint(time, tick / 480.0, tick, probability)
        }
        val sections = SectionAnalyzer.analyze(features.rms, durationSec, bpm, AutoChartConfig.HOP_LENGTH)
        progress(60, "检测到 ${points.size} 个踩音点，BPM ${"%.2f".format(java.util.Locale.US, bpm)}")
        return AutoAnalysis(
            bpm, offset, durationSec, points,
            listOf(AutoTempo(0, bpm)), onsetNorm = features.onset, rmsNorm = features.rms,
            bands = features.bands, sections = sections,
        )
    }

    private fun estimateBpm(env: DoubleArray): Double {
        if (env.size < 8) return 120.0
        val framesPerMinute = 60.0 * AutoChartConfig.SAMPLE_RATE / AutoChartConfig.HOP_LENGTH
        val minLag = (framesPerMinute / 200).roundToInt()
        val maxLag = (framesPerMinute / 60).roundToInt().coerceAtMost(env.size / 2)
        if (maxLag < minLag) return 120.0

        val mean = env.average()
        val correlations = DoubleArray(maxLag + 2)
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var score = 0.0
            for (i in lag until env.size) score += (env[i] - mean) * (env[i - lag] - mean)
            correlations[lag] = score / (env.size - lag)
        }
        val candidates = (minLag..maxLag).mapNotNull { lag ->
            val center = correlations[lag]
            val left = correlations[lag - 1]
            val right = correlations[lag + 1]
            if (center <= 1e-12 || center < left || center < right) return@mapNotNull null
            val curvature = left - 2 * center + right
            val shift = if (curvature < 0) (.5 * (left - right) / curvature).coerceIn(-.5, .5) else 0.0
            val bpm = (framesPerMinute / (lag + shift)).coerceIn(60.0, 200.0)
            val peak = center - .25 * (left - right) * shift
            val tempoPrior = exp(-.5 * (ln(bpm / 120.0) / .65).pow(2))
            bpm to peak * tempoPrior
        }
        return candidates.maxByOrNull { it.second }?.first ?: 120.0
    }

    private fun trackBeats(env: DoubleArray, bpm: Double): List<Double> {
        val period = 60.0 * AutoChartConfig.SAMPLE_RATE / (bpm * AutoChartConfig.HOP_LENGTH)
        if (!period.isFinite() || period < 1 || env.isEmpty()) return emptyList()
        val radius = max(1, (period * .12).toInt())
        val smooth = DoubleArray(env.size) { i ->
            val first = max(0, i - 2)
            val last = min(env.lastIndex, i + 2)
            var sum = 0.0
            for (j in first..last) sum += env[j]
            sum / (last - first + 1)
        }
        val best = DoubleArray(env.size)
        val previous = IntArray(env.size) { -1 }
        for (i in smooth.indices) {
            best[i] = smooth[i]
            var lag = max(1, (period - radius).toInt())
            val lastLag = (period + radius).toInt()
            while (lag <= lastLag && i - lag >= 0) {
                val score = best[i - lag] + smooth[i] - .15 * abs(lag - period) / period
                if (score > best[i]) { best[i] = score; previous[i] = i - lag }
                lag++
            }
        }
        var index = best.indices.maxByOrNull { best[it] } ?: return emptyList()
        val frames = mutableListOf<Int>()
        while (index >= 0) { frames += index; index = previous[index] }
        frames.reverse()
        val hopSec = AutoChartConfig.HOP_LENGTH.toDouble() / AutoChartConfig.SAMPLE_RATE
        return frames.map { it * hopSec }.distinct()
    }

    private fun peakFrames(env: DoubleArray): List<Int> {
        if (env.isEmpty()) return emptyList()
        val delta = .08
        val wait = (AutoChartConfig.HOP_LENGTH / 128).coerceAtLeast(1)
        val peaks = mutableListOf<Int>()
        var last = -wait
        for (i in env.indices) {
            if (i - last < wait) continue
            val from = max(0, i - 3)
            val to = min(env.lastIndex, i + 3)
            if (env[i] < delta || (from..to).any { env[it] > env[i] }) continue
            val avgFrom = max(0, i - 3)
            val avgTo = min(env.lastIndex, i + 5)
            var sum = 0.0
            for (index in avgFrom..avgTo) sum += env[index]
            val average = sum / (avgTo - avgFrom + 1)
            if (env[i] < average + delta * .5) continue
            peaks += i
            last = i
        }
        return peaks
    }

    private fun timeToTick(timeSec: Double, tempos: List<AutoTempo>, ticksPerBeat: Int): Double {
        var elapsed = 0.0
        for (index in tempos.indices) {
            val event = tempos[index]
            val nextTick = tempos.getOrNull(index + 1)?.beatTick
            val secondsPerTick = 60.0 / (event.bpm * ticksPerBeat)
            if (nextTick == null) return event.beatTick + (timeSec - elapsed) / secondsPerTick
            val segmentSeconds = (nextTick - event.beatTick) * secondsPerTick
            if (timeSec <= elapsed + segmentSeconds) return event.beatTick + (timeSec - elapsed) / secondsPerTick
            elapsed += segmentSeconds
        }
        return 0.0
    }

    private fun validateImportedTimeline(imported: AutoAnalysis, actualDurationSec: Double) {
        val hopTolerance = AutoChartConfig.HOP_LENGTH.toDouble() / AutoChartConfig.SAMPLE_RATE
        val durationTolerance = max(0.25, hopTolerance * 2)
        require(abs(imported.durationSec - actualDurationSec) <= durationTolerance) { "踩音文件与当前音频时长不匹配" }
        require(imported.points.all { it.timeSec in 0.0..(actualDurationSec + hopTolerance) }) { "踩音点超出当前音频范围" }
        require(imported.tempoEvents.isNotEmpty()) { "踩音文件缺少速度事件" }
        val tempos = imported.tempoEvents.sortedBy { it.beatTick }
        require(tempos.first().beatTick == 0 && tempos.all { it.beatTick >= 0 && it.bpm.isFinite() && it.bpm > 0.0 }) { "踩音文件速度事件无效" }
        val tickTolerance = 2.0
        for (point in imported.points) {
            val expectedTick = timeToTick(point.timeSec, tempos, imported.ticksPerBeat)
            require(abs(point.beat * imported.ticksPerBeat - point.beatTick) <= tickTolerance &&
                abs(point.beatTick - expectedTick) <= tickTolerance) { "踩音点时轴不一致" }
        }
    }

}

private fun roundToIntSafe(value: Double): Int = when {
    value >= Int.MAX_VALUE -> Int.MAX_VALUE
    value <= Int.MIN_VALUE -> Int.MIN_VALUE
    else -> kotlin.math.round(value).toInt()
}
