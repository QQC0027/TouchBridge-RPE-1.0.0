package com.touchbridge.rpe.domain.autochart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineFxTest {
    private val analysis = AutoAnalysis(
        bpm = 120.0, offsetSec = 0.0, durationSec = 24.0, points = emptyList(), tempoEvents = emptyList(),
        sections = listOf(
            AutoSection(0.0, 4.0, "intro/break", .25),
            AutoSection(4.0, 12.0, "verse", .55),
            AutoSection(12.0, 24.0, "chorus", .85),
        ),
    )
    private val notes = (2..46 step 2).map { beat ->
        buildJsonObject {
            put("startTime", JsonArray(listOf(beat, 0, 1).map(::JsonPrimitive)))
            put("type", if (beat % 4 == 0) AutoChartConfig.FLICK else AutoChartConfig.TAP)
            put("positionX", -150)
            put("above", 1)
            put("isFake", 0)
        }
    }

    @Test
    fun eachModeProducesItsExpectedEvents() {
        val modes = mapOf(
            "alpha_pulse" to "侧闪线", "pinwheel" to "pinwheel", "grid" to "竖线",
            "curve" to "curve_traj", "meteor" to "curve_traj", "echo_trail" to "echo", "spotlight" to "spotlight",
        )
        for ((mode, prefix) in modes) {
            val lines = chartLines(AutoChartOptions(lineFx = mode))
            assertTrue(mode, lines.drop(1).any { it.getValue("Name").jsonPrimitive.content.startsWith(prefix) })
            for (line in lines) {
                val incline = line.getValue("extended").jsonObject.getValue("inclineEvents").jsonArray
                assertTrue(mode, incline.isNotEmpty())
                val layer = line.getValue("eventLayers").jsonArray.single().jsonObject
                for (key in listOf("alphaEvents", "moveXEvents", "moveYEvents", "rotateEvents", "speedEvents")) {
                    assertTrue("$mode/$key", layer.getValue(key).jsonArray.isNotEmpty())
                }
            }
        }
        for ((mode, keys) in mapOf(
            "color_flow" to listOf("colorEvents"), "glow" to listOf("colorEvents"),
            "breathe" to listOf("scaleXEvents", "scaleYEvents"),
        )) {
            val extended = chartLines(AutoChartOptions(lineFx = mode)).first().getValue("extended").jsonObject
            for (key in keys) assertTrue("$mode/$key", extended.getValue(key).jsonArray.isNotEmpty())
        }
        assertTrue(mainEvents(AutoChartOptions(lineFx = "bounce"), "moveYEvents").size > 1)
        assertTrue(mainEvents(AutoChartOptions(mainSwing = true), "rotateEvents").size > 1)
    }

    @Test
    fun mixPreservesEffectOrderAndChangesLiftedNotes() {
        val options = AutoChartOptions(lineFx = "mix", mixSpec = "pinwheel@all;grid@all")
        val lines = chartLines(options)
        assertEquals("pinwheel1", lines[1].getValue("Name").jsonPrimitive.content)
        assertEquals("竖线1", lines[7].getValue("Name").jsonPrimitive.content)
        val fake = lines[1].getValue("notes").jsonArray
        assertTrue(fake.isNotEmpty())
        assertTrue(fake.all { it.jsonObject.getValue("isFake").jsonPrimitive.int == 1 })
        val primaryNotes = lines.first().getValue("notes").jsonArray
        assertTrue(primaryNotes.any { it.jsonObject.getValue("above").jsonPrimitive.int == 2 })
    }

    @Test
    fun configuredPositionAppliesWithoutEffectsIncludingDefault() {
        for (mainY in listOf(-300.0, -175.0)) {
            val event = mainEvents(AutoChartOptions(mainY = mainY), "moveYEvents").single().jsonObject
            assertEquals(mainY, event.getValue("start").jsonPrimitive.double, 0.0)
            assertEquals(mainY, event.getValue("end").jsonPrimitive.double, 0.0)
            assertEquals(1, chartLines(AutoChartOptions(mainY = mainY)).size)
        }
    }

    @Test
    fun mixBouncePreservesLiftAndMatchingNoteSide() {
        val options = AutoChartOptions(lineFx = "mix", mixSpec = "bounce@all;grid@all")
        val primary = chartLines(options).first()
        val lifted = primary.getValue("notes").jsonArray.single {
            beat(it.jsonObject.getValue("startTime")) == 28.0
        }.jsonObject
        assertEquals(2, lifted.getValue("above").jsonPrimitive.int)
        assertEquals(200.0, valueAt(mainEvents(options, "moveYEvents"), 29.0), 1e-6)
        assertTrue(valueAt(mainEvents(options, "moveYEvents"), 28.1) > -100.0)
        val events = mainEvents(options, "moveYEvents").map { it.jsonObject }
        assertTrue(events.zipWithNext().all { (a, b) -> beat(a.getValue("endTime")) <= beat(b.getValue("startTime")) })
    }

    @Test
    fun mixSectionSelectorsRespectMatchesAndEmptyMatches() {
        val spotlight = chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "spotlight@intro"))
            .drop(1).filter { it.getValue("Name").jsonPrimitive.content.startsWith("spotlight") }
        assertEquals(1, spotlight.size)
        val alpha = spotlight.single().getValue("eventLayers").jsonArray.single().jsonObject
            .getValue("alphaEvents").jsonArray.map { it.jsonObject }
        assertTrue(alpha.all { beat(it.getValue("endTime")) <= 8.0 })

        for ((spec, prefix) in listOf("sweep@missing" to "侧闪线", "grid@missing" to "竖线")) {
            val effectLines = chartLines(AutoChartOptions(lineFx = "mix", mixSpec = spec)).drop(1)
                .filter { it.getValue("Name").jsonPrimitive.content.startsWith(prefix) }
            assertTrue(effectLines.all { line ->
                line.getValue("eventLayers").jsonArray.single().jsonObject.getValue("alphaEvents").jsonArray
                    .all { event -> event.jsonObject.getValue("start").jsonPrimitive.double == 0.0 && event.jsonObject.getValue("end").jsonPrimitive.double == 0.0 }
            })
        }
        assertTrue(chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "sweep@all")).size > 1)
        assertTrue(chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "grid@all")).size > 1)
        assertEquals(1, chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "pinwheel@missing")).size)
        assertEquals(1, chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "spotlight@missing")).size)
        assertEquals(1, chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "curve@missing")).size)
        assertEquals(1, chartLines(AutoChartOptions(lineFx = "mix", mixSpec = "meteor@missing")).size)
    }

    @Test
    fun defaultPositionSurvivesSwingModes() {
        for (options in listOf(AutoChartOptions(mainSwing = true), AutoChartOptions(lineFx = "main_swing"))) {
            val y = mainEvents(options, "moveYEvents").single().jsonObject
            assertEquals(-300.0, y.getValue("start").jsonPrimitive.double, 0.0)
            assertEquals(-300.0, y.getValue("end").jsonPrimitive.double, 0.0)
        }
    }

    @Test
    fun highBpmAdjacentBouncesStayContinuousAndAddOverlaps() {
        val fast = analysis.copy(bpm = 180.0)
        val options = AutoChartOptions(lineFx = "mix", mixSpec = "bounce@all")
        val events = ChartJsonBuilder.build(notes, fast, JsonObject(emptyMap()), options)
            .getValue("judgeLineList").jsonArray.first().jsonObject.getValue("eventLayers").jsonArray.single().jsonObject
            .getValue("moveYEvents").jsonArray
        val parsed = events.map { it.jsonObject }
        assertTrue(parsed.zipWithNext().all { (a, b) ->
            beat(a.getValue("endTime")) <= beat(b.getValue("startTime")) &&
                kotlin.math.abs(a.getValue("end").jsonPrimitive.double - b.getValue("start").jsonPrimitive.double) < 1e-6
        })
        assertTrue(kotlin.math.abs(valueAt(events, 29.05) - 200.0) > 1e-3)
    }

    private fun valueAt(events: JsonArray, at: Double): Double {
        val event = events.map { it.jsonObject }.last { at >= beat(it.getValue("startTime")) && at <= beat(it.getValue("endTime")) }
        val from = beat(event.getValue("startTime"))
        val to = beat(event.getValue("endTime"))
        val start = event.getValue("start").jsonPrimitive.double
        val end = event.getValue("end").jsonPrimitive.double
        return if (to <= from) end else start + (end - start) * ((at - from) / (to - from))
    }

    private fun beat(value: kotlinx.serialization.json.JsonElement): Double {
        val fraction = value.jsonArray
        return fraction[0].jsonPrimitive.double + fraction[1].jsonPrimitive.double / fraction[2].jsonPrimitive.double
    }

    private fun chartLines(options: AutoChartOptions) = ChartJsonBuilder.build(notes, analysis, JsonObject(emptyMap()), options)
        .getValue("judgeLineList").jsonArray.map { it.jsonObject }

    private fun mainEvents(options: AutoChartOptions, key: String) = chartLines(options).first()
        .getValue("eventLayers").jsonArray.single().jsonObject.getValue(key).jsonArray
}
