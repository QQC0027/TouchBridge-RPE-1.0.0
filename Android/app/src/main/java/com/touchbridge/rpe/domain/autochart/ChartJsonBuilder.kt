package com.touchbridge.rpe.domain.autochart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.round

object ChartJsonBuilder {
    private val start = fraction(0, 1)
    private val end = fraction(999999, 1)
    private val bezier = JsonArray(List(4) { JsonPrimitive(0.0) })

    fun build(notes: List<JsonObject>, analysis: AutoAnalysis, meta: JsonObject, options: AutoChartOptions): JsonObject {
        val fx = LineFx.apply(notes, analysis, options)
        val tempos = analysis.tempoEvents.ifEmpty { listOf(AutoTempo(0, analysis.bpm)) }
        return buildJsonObject {
            put("BPMList", JsonArray(tempos.distinctBy { it.beatTick }.map { tempo ->
                buildJsonObject {
                    put("startTime", fraction(tempo.beatTick, analysis.ticksPerBeat))
                    put("bpm", tempo.bpm)
                }
            }))
            put("META", meta)
            putJsonArray("judgeLineGroup") { add(JsonPrimitive("Default")) }
            putJsonArray("judgeLineList") {
                add(buildJsonObject {
                    put("Group", 0)
                    put("Name", "主判定线")
                    put("Texture", "line.png")
                    put("bpmfactor", 1.0)
                    put("eventLayers", fx.events ?: defaultEvents())
                    put("extended", buildJsonObject {
                        put("inclineEvents", JsonArray(listOf(event(start, fraction(1, 1), 0.0, 0.0, 0))))
                        fx.extended.forEach { (key, value) -> put(key, value) }
                    })
                    put("father", -1)
                    put("isCover", 1)
                    put("notes", JsonArray(fx.notes))
                    put("numOfNotes", fx.notes.size)
                    for ((key, value) in listOf("alpha" to 1.0, "pos" to 1.0, "size" to 1.0, "skew" to 0.0, "y" to 1.0)) {
                        put("${key}Control", JsonArray(listOf(0.0, 9999999.0).map { x ->
                            buildJsonObject { put("easing", 1); put(key, value); put("x", x) }
                        }))
                    }
                    put("zOrder", 0)
                })
                fx.lines.forEach { add(it) }
            }
        }
    }

    fun metadata(id: String, title: String, level: String, charter: String, composer: String,
                 audioExtension: String, analysis: AutoAnalysis): JsonObject = buildJsonObject {
        put("RPEVersion", AutoChartConfig.RPE_VERSION)
        put("background", "$id.png")
        put("charter", charter.ifBlank { "TouchBridge" })
        put("composer", composer)
        put("illustration", "$id.png")
        put("id", id)
        put("level", level)
        put("name", title)
        put("offset", round(analysis.offsetSec * 1000).toInt())
        put("song", "$id$audioExtension")
        put("duration", analysis.durationSec)
    }

    private fun defaultEvents() = JsonArray(listOf(buildJsonObject {
        for ((key, value) in listOf("alpha" to 255.0, "moveX" to 0.0, "moveY" to 0.0, "rotate" to 0.0)) {
            put("${key}Events", JsonArray(listOf(event(start, end, value, value))))
        }
        put("speedEvents", JsonArray(listOf(buildJsonObject {
            put("startTime", start)
            put("endTime", end)
            put("start", AutoChartConfig.DEFAULT_SPEED)
            put("end", AutoChartConfig.DEFAULT_SPEED)
            put("linkgroup", 0)
        })))
    }))

    private fun event(startTime: JsonArray, endTime: JsonArray, startValue: Double, endValue: Double, ease: Int = 1) = buildJsonObject {
        put("bezier", 0)
        put("bezierPoints", bezier)
        put("easingLeft", 0.0)
        put("easingRight", 1.0)
        put("easingType", ease)
        put("start", startValue)
        put("end", endValue)
        put("startTime", startTime)
        put("endTime", endTime)
        put("linkgroup", 0)
    }

    private fun fraction(tick: Int, denominator: Int): JsonArray {
        val whole = Math.floorDiv(tick, denominator)
        val numerator = Math.floorMod(tick, denominator)
        var a = numerator
        var b = denominator
        while (b != 0) { val remainder = a % b; a = b; b = remainder }
        return JsonArray(listOf(whole, numerator / a, denominator / a).map(::JsonPrimitive))
    }
}
