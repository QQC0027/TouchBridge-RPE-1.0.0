package com.touchbridge.rpe.engine

import com.touchbridge.rpe.domain.engine.BeatClock
import com.touchbridge.rpe.domain.engine.RpeBuilder
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 用 Python 版 engine.py 生成的黄金 JSON 逐字段校验 Kotlin 移植结果。
 * 生成脚本：.buildtools/gen_golden.py（不改动 PC 版代码）。
 */
class EngineGoldenTest {

    private fun loadFixture(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden/$name.json")
        assertNotNull("missing fixture golden/$name.json", stream)
        return Json.parseToJsonElement(stream!!.bufferedReader().readText()).jsonObject
    }

    private fun parseGestures(root: JsonObject): List<GestureNote> =
        root["gestures"]!!.jsonArray.map { element ->
            val o = element.jsonObject
            val gesture = when (o["gesture"]!!.jsonPrimitive.content) {
                "tap" -> Gesture.TAP
                "hold" -> Gesture.HOLD
                "flick" -> Gesture.FLICK
                else -> Gesture.DRAG
            }
            val path = o["path"]?.jsonArray?.map { p ->
                val a = p.jsonArray
                TrackPoint(
                    t = a[0].jsonPrimitive.double,
                    x = a[1].jsonPrimitive.double,
                    y = a[2].jsonPrimitive.double,
                )
            } ?: emptyList()
            val dir = o["dir"]?.jsonArray?.let { d ->
                Pair(d[0].jsonPrimitive.double, d[1].jsonPrimitive.double)
            }
            GestureNote(
                gesture = gesture,
                finger = o["finger"]!!.jsonPrimitive.int,
                tSec = o["t_sec"]!!.jsonPrimitive.double,
                x = o["x"]!!.jsonPrimitive.double,
                holdSec = o["hold_sec"]?.jsonPrimitive?.double ?: 0.0,
                path = path,
                dir = dir,
                gid = o["gid"]?.jsonPrimitive?.int ?: 0,
            )
        }

    private fun parseSettings(root: JsonObject): ChartSettings {
        val s = root["settings"]!!.jsonObject
        return ChartSettings(
            bpm = root["bpm"]!!.jsonPrimitive.double,
            offsetMs = root["offset_ms"]!!.jsonPrimitive.double,
            beatsPerBar = root["beats_per_bar"]!!.jsonPrimitive.int,
            subdivision = s["subdivision"]!!.jsonPrimitive.int,
            snapTolerance = s["snap_tolerance"]!!.jsonPrimitive.double,
            debounceMs = s["debounce_ms"]!!.jsonPrimitive.double,
            xMode = s["x_mode"]!!.jsonPrimitive.content,
            regions = s["regions"]!!.jsonPrimitive.int,
            dragSampleMs = s["drag_sample_ms"]!!.jsonPrimitive.double,
            hitOffsetMs = s["hit_offset_ms"]!!.jsonPrimitive.double,
        )
    }

    private fun parseMeta(root: JsonObject): ChartMeta {
        val m = root["meta"]!!.jsonObject
        return ChartMeta(
            name = m["name"]!!.jsonPrimitive.content,
            charter = m["charter"]!!.jsonPrimitive.content,
            composer = m["composer"]!!.jsonPrimitive.content,
            level = m["level"]!!.jsonPrimitive.content,
            id = m["id"]!!.jsonPrimitive.content,
        )
    }

    private fun assertBbtEquals(tag: String, expected: JsonArray, actual: JsonArray) {
        assertEquals("$tag length", expected.size, actual.size)
        for (i in 0 until expected.size) {
            assertEquals(
                "$tag[$i]",
                expected[i].jsonPrimitive.int,
                actual[i].jsonPrimitive.int,
            )
        }
    }

    private fun assertMatchesGolden(fixture: String) {
        val root = loadFixture(fixture)
        val settings = parseSettings(root)
        val meta = parseMeta(root)
        val clock = BeatClock(
            bpm = settings.bpm,
            offsetMs = settings.offsetMs,
            beatsPerBar = settings.beatsPerBar,
        )
        val gestures = parseGestures(root)
        val result = RpeBuilder.build(gestures, clock, settings, meta, Random(7))

        val actualNotes = result.rpe["judgeLineList"]!!.jsonArray[0].jsonObject["notes"]!!.jsonArray
        val expectedNotes = root["notes"]!!.jsonArray
        assertEquals("$fixture note count", expectedNotes.size, actualNotes.size)

        for (i in expectedNotes.indices) {
            val e = expectedNotes[i].jsonObject
            val a = actualNotes[i].jsonObject
            assertEquals("$fixture type@$i", e["type"]!!.jsonPrimitive.int, a["type"]!!.jsonPrimitive.int)
            assertBbtEquals("$fixture start@$i", e["startTime"]!!.jsonArray, a["startTime"]!!.jsonArray)
            assertBbtEquals("$fixture end@$i", e["endTime"]!!.jsonArray, a["endTime"]!!.jsonArray)
            val expectedX = e["positionX"]!!.jsonPrimitive.double
            val actualX = a["positionX"]!!.jsonPrimitive.double
            assertEquals("$fixture positionX@$i", expectedX, actualX, 1e-9)
            val expectedStartBeat = e["startBeat"]?.jsonPrimitive?.double
            if (expectedStartBeat != null) {
                assertEquals("$fixture startBeat@$i", expectedStartBeat, result.notes[i].startBeat, 1e-6)
            }
        }

        val expectedStats = root["stats"]!!.jsonObject
        assertEquals("$fixture tap", expectedStats["tap"]!!.jsonPrimitive.int, result.stats.tap)
        assertEquals("$fixture hold", expectedStats["hold"]!!.jsonPrimitive.int, result.stats.hold)
        assertEquals("$fixture flick", expectedStats["flick"]!!.jsonPrimitive.int, result.stats.flick)
        assertEquals("$fixture drag", expectedStats["drag"]!!.jsonPrimitive.int, result.stats.drag)

        val expectedBpm = root["bpm_list"]!!.jsonArray
        val actualBpm = result.rpe["BPMList"]!!.jsonArray
        assertEquals("$fixture bpm segments", expectedBpm.size, actualBpm.size)
        for (i in expectedBpm.indices) {
            val e = expectedBpm[i].jsonObject
            val a = actualBpm[i].jsonObject
            assertEquals(
                "$fixture bpm@$i",
                e["bpm"]!!.jsonPrimitive.double,
                a["bpm"]!!.jsonPrimitive.double,
                1e-9,
            )
            assertEquals(
                "$fixture startBeat@$i",
                e["startBeat"]!!.jsonPrimitive.double,
                a["startBeat"]!!.jsonPrimitive.double,
                1e-9,
            )
        }

        // 内部拍位与 Python 的 startBeat 应完全一致
        for (i in expectedNotes.indices) {
            val expectedStart = expectedNotes[i].jsonObject["startBeat"]?.jsonPrimitive?.double ?: continue
            assertEquals("$fixture internal start@$i", expectedStart, result.notes[i].startBeat, 1e-6)
            assert(abs(result.notes[i].positionX) <= 675.0)
        }
    }

    @Test
    fun `sessionA matches python golden output`() {
        assertMatchesGolden("session_a")
    }

    @Test
    fun `sessionB matches python golden output`() {
        assertMatchesGolden("session_b")
    }
}
