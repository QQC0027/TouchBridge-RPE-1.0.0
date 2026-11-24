package com.touchbridge.rpe.domain.autochart

import com.touchbridge.rpe.domain.model.ChartMeta
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.round

object AutoChartFeature { const val ENABLED = true }

data class AutoChartResult(val tier: String, val chartId: String, val level: String, val noteCount: Int, val rpe: JsonObject)
data class AutoChartOutput(val analysis: AutoAnalysis, val charts: List<AutoChartResult>, val pointsJson: String)

object AutoChartEngine {
    suspend fun generate(file: File, meta: ChartMeta, options: AutoChartOptions, pointsJson: String? = null,
                         progress: (Int, String) -> Unit = { _, _ -> }): AutoChartOutput = withContext(Dispatchers.Default) {
        val imported = pointsJson?.let(::readPoints)
        val audio = AudioAnalyzer.analyze(file, progress, imported)
        require(audio.points.isNotEmpty()) { "没有检测到可用于制谱的踩音点" }
        val tiers = if (options.allTiers) AutoChartConfig.presets.keys.toList() else listOf(options.tier)
        val title = meta.name.trim().ifEmpty { file.nameWithoutExtension }
        val extension = file.extension.lowercase(Locale.ROOT).ifEmpty { "mp3" }
        progress(61, "排谱种子：${options.seed}")
        val charts = tiers.mapIndexed { index, tier ->
            ensureActive()
            val preset = AutoChartConfig.presets.getValue(tier)
            val notes = Charter.generate(audio, options, tier)
            val id = chartId(title, tier)
            val level = "${preset.label} ${preset.level}"
            val metadata = ChartJsonBuilder.metadata(id, title, level, meta.charter, meta.composer, ".$extension", audio)
            val chart = ChartJsonBuilder.build(notes, audio, metadata, options)
            ensureActive()
            progress(63 + 29 * (index + 1) / tiers.size, "$level：${notes.size} 个音符")
            AutoChartResult(tier, id, level, notes.size, chart)
        }
        AutoChartOutput(audio, charts, writePoints(audio))
    }

    private fun chartId(name: String, tier: String): String {
        val slug = name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "song" }.take(24)
        val digest = MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)).take(4)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
        return "${slug}_${digest}_$tier"
    }

    internal fun readPoints(text: String): AutoAnalysis {
        val input = Json.parseToJsonElement(text)
        val header = input as? JsonObject
        val list = (input as? JsonArray) ?: (header?.get("points") as? JsonArray)
        require(list != null && list.size in 1..100_000) { "踩音点数量无效" }
        val bpm = header?.number("bpm") ?: 120.0
        val ticks = header?.integer("ticks_per_beat") ?: 480
        val offset = header?.number("offset_sec") ?: 0.0
        require(bpm in 20.0..400.0 && ticks in 1..9600 && offset in -60.0..60.0) { "踩音文件的 BPM、拍刻度或偏移无效" }
        val points = list.map {
            val point = it.jsonObject
            val time = point.number("time_sec") ?: 0.0
            require(time in 0.0..7200.0) { "踩音点时间无效" }
            val tick = point.integer("beat_tick") ?: round(time * bpm / 60 * ticks).toInt()
            val beat = point.number("beat") ?: tick.toDouble() / ticks
            val probability = point.number("probability") ?: 0.4
            require(tick in -9600..500_000_000 && beat in -10.0..50000.0 && probability.isFinite()) { "踩音点超出有效范围" }
            AutoPoint(time, beat, tick, probability.coerceIn(0.05, 1.0), point.integer("snap_status") ?: 1)
        }.sortedBy { it.beatTick }
        val duration = header?.number("duration_sec") ?: (points.maxOf { it.timeSec } + 120 / bpm)
        require(duration > 0 && duration <= 7200 && points.all { it.timeSec <= duration }) { "踩音文件时长无效" }
        val tempos = (header?.get("tempo_events") as? JsonArray).orEmpty().map {
            val event = it.jsonObject
            val tick = event.integer("beat_tick") ?: 0
            val tempo = event.number("bpm") ?: bpm
            require(tick in 0..500_000_000 && tempo in 20.0..400.0) { "变速事件无效" }
            AutoTempo(tick, tempo)
        }.sortedBy { it.beatTick }.ifEmpty { listOf(AutoTempo(0, bpm)) }
        return AutoAnalysis(bpm, offset, duration, points, tempos, ticks)
    }

    private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.integer(key: String) = get(key)?.jsonPrimitive?.intOrNull

    private fun writePoints(result: AutoAnalysis): String = buildJsonObject {
        put("bpm", result.bpm)
        put("ticks_per_beat", result.ticksPerBeat)
        put("offset_sec", result.offsetSec)
        put("duration_sec", result.durationSec)
        putJsonArray("tempo_events") { result.tempoEvents.forEach { add(buildJsonObject { put("beat_tick", it.beatTick); put("bpm", it.bpm) }) } }
        putJsonArray("points") { result.points.forEach { add(buildJsonObject {
            put("time_sec", it.timeSec); put("beat", it.beat); put("beat_tick", it.beatTick)
            put("probability", it.probability); put("snap_status", it.snapStatus)
        }) } }
    }.toString()
}
