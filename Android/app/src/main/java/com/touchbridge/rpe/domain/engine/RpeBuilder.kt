package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.Beat
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.RpeNote
import com.touchbridge.rpe.domain.model.Rounder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.random.Random

data class ChartStats(val tap: Int, val hold: Int, val flick: Int, val drag: Int) {
    val total: Int get() = tap + hold + flick + drag
}

data class RpeBuildResult(
    val rpe: JsonObject,
    val notes: List<RpeNote>,
    val stats: ChartStats,
)

/** 逐行对应 engine.py 的 build_rpe()：生成与 PC 版结构一致的 RPE JSON。 */
object RpeBuilder {

    private const val EASING_LINEAR = 1

    fun build(
        notes: List<GestureNote>,
        clock: BeatClock,
        settings: ChartSettings,
        meta: ChartMeta,
        random: Random = Random.Default,
    ): RpeBuildResult {
        val converted = NoteConverter.convert(notes, clock, settings)

        var tap = 0
        var hold = 0
        var flick = 0
        var drag = 0
        val rpeNotes = buildJsonArray {
            for (n in converted) {
                add(noteToJson(n))
                when (n.type) {
                    1 -> tap++
                    2 -> hold++
                    3 -> flick++
                    4 -> drag++
                }
            }
        }
        val stats = ChartStats(tap, hold, flick, drag)

        val maxBeat = converted.maxOfOrNull { it.endBeat } ?: 0.0
        val endBeat = max(64.0, maxBeat + 16.0)
        val endBbt = clock.beatToBbt(endBeat)
        val zeroBbt = clock.beatToBbt(0.0)

        val speedEvent = buildJsonObject {
            put("startTime", zeroBbt.json())
            put("endTime", endBbt.json())
            put("value", 10.0)
            put("floorPosition", 0.0)
            put("start", 10.0)
            put("end", 10.0)
        }

        fun lineEvent(startVal: Double, endVal: Double): JsonObject = buildJsonObject {
            put("startTime", zeroBbt.json())
            put("endTime", endBbt.json())
            put("start", startVal)
            put("end", endVal)
            put("easingType", EASING_LINEAR)
            put("easingLeft", 0.0)
            put("easingRight", 1.0)
            put("bezier", 0)
            put("bezierPoints", buildJsonArray { add(JsonPrimitive(0.0)); add(JsonPrimitive(0.0)); add(JsonPrimitive(1.0)); add(JsonPrimitive(1.0)) })
            put("linkgroup", 0)
        }

        val defaultLayer = buildJsonObject {
            put("speedEvents", buildJsonArray { add(speedEvent) })
            put("moveXEvents", buildJsonArray { add(lineEvent(0.0, 0.0)) })
            put("moveYEvents", buildJsonArray { add(lineEvent(0.0, 0.0)) })
            put("rotateEvents", buildJsonArray { add(lineEvent(0.0, 0.0)) })
            put("alphaEvents", buildJsonArray { add(lineEvent(255.0, 255.0)) })
        }

        val judgeLine = buildJsonObject {
            put("Group", 0)
            put("Name", "TouchBridge Line")
            put("Texture", "line.png")
            put("anchor", buildJsonArray { add(JsonPrimitive(0.5)); add(JsonPrimitive(0.5)) })
            put("bpmfactor", 1.0)
            put("rotateWithFather", true)
            put("isGif", false)
            put("eventLayers", buildJsonArray { add(defaultLayer) })
            put("extended", JsonObject(emptyMap()))
            put("father", -1)
            put("isCover", 1)
            put("numOfNotes", converted.size)
            put("zOrder", 0)
            put("notes", rpeNotes)
        }

        val bpmList = buildJsonArray {
            for (s in clock.segments) {
                add(
                    buildJsonObject {
                        put("startTime", clock.beatToBbt(s.startBeat).json())
                        put("bpm", s.bpm)
                        put("BPM", s.bpm)
                        put("startBeat", s.startBeat)
                        put("startTimeSec", Rounder.round(clock.beatToTime(s.startBeat), 6))
                    },
                )
            }
        }

        val rpeId = meta.id.ifBlank { random.nextInt(100000, 1000000).toString() }

        val rpe = buildJsonObject {
            put("BPMList", bpmList)
            put(
                "META",
                buildJsonObject {
                    put("RPEVersion", 160)
                    put("background", meta.background)
                    put("charter", meta.charter.ifBlank { "TouchBridge" })
                    put("composer", meta.composer)
                    put("id", rpeId)
                    put("illustration", meta.cover)
                    put("level", meta.level)
                    put("name", meta.name.ifBlank { "Untitled" })
                    put("offset", Math.rint(clock.offsetSec * 1000.0).toInt())
                    put("song", meta.song)
                },
            )
            put(
                "judgeLineGroup",
                buildJsonArray { add(JsonPrimitive("Default")); repeat(4) { add(JsonPrimitive("")) } },
            )
            put("judgeLineList", buildJsonArray { add(judgeLine) })
        }

        return RpeBuildResult(rpe, converted, stats)
    }

    fun noteToJson(n: RpeNote): JsonObject = buildJsonObject {
        put("type", n.type)
        put("startTime", n.startTime.json())
        put("endTime", n.endTime.json())
        put("positionX", n.positionX)
        put("above", n.above)
        put("alpha", n.alpha)
        put("size", n.size)
        put("speed", n.speed)
        put("yOffset", n.yOffset)
        put("visibleTime", n.visibleTime)
        put("isFake", n.isFake)
    }

    private fun Beat.json(): JsonArray = buildJsonArray {
        add(JsonPrimitive(whole))
        add(JsonPrimitive(num))
        add(JsonPrimitive(den))
    }

    @Suppress("unused")
    private val unusedNull = JsonNull
}
