package com.touchbridge.rpe.domain.autochart

import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class CharterGoldenTest {
    @Test
    fun hardChorusMatchesPythonReference() {
        val points = (0..24).map { Charter.Point(it / 4.0, it / 2.0, it * 240, 0.9) }
        val sections = listOf(Charter.Section(0.0, 6.25, "chorus", 0.85))
        val result = Charter.generate(
            points, sections, 120.0, 480,
            linkedMapOf("low" to DoubleArray(280), "mid" to DoubleArray(280) { 1.0 }, "high" to DoubleArray(280)),
            DoubleArray(280) { 0.65 }, DoubleArray(280), AutoChartConfig.SAMPLE_RATE, AutoChartOptions(seed = 42L),
            hopLength = AutoChartConfig.HOP_LENGTH,
        )
        val actual = result.map {
            val start = it.getValue("startTime").jsonArray.map { value -> value.jsonPrimitive.int }
            assertEquals(listOf(0, 1), start.drop(1))
            assertEquals(it["startTime"], it["endTime"])
            Triple(start[0], it.getValue("type").jsonPrimitive.int, it.getValue("positionX").jsonPrimitive.double.toInt())
        }
        assertEquals(listOf(
            Triple(2, 1, -150), Triple(3, 3, -450), Triple(3, 3, 450), Triple(4, 3, 300),
            Triple(4, 4, 300), Triple(5, 3, -300), Triple(5, 4, -300), Triple(6, 3, 150),
            Triple(7, 1, 150), Triple(8, 1, 150), Triple(9, 1, -150), Triple(10, 1, -300),
            Triple(11, 1, -150), Triple(12, 1, -300),
        ), actual)
    }

    @Test
    fun allFiveTiersMatchPythonAcrossRepeatedSections() {
        val points = (0 until 256).map { Charter.Point(it / 8.0, it / 4.0, it * 120, 0.6 + it % 4 * 0.1) }
        val sections = listOf("intro/break", "verse", "chorus", "chorus", "verse", "chorus", "chorus", "chorus")
            .mapIndexed { i, label ->
                val density = when (label) { "intro/break" -> 0.25; "verse" -> 0.55; else -> 0.85 }
                Charter.Section(i * 4.0, i * 4.0 + 4.0, label, density)
            }
        val frameCount = 1380
        val bands = linkedMapOf("low" to DoubleArray(frameCount), "mid" to DoubleArray(frameCount) { 1.0 }, "high" to DoubleArray(frameCount))
        val onset = DoubleArray(frameCount) { (it % 13 + 1) / 16.0 }
        val rms = DoubleArray(frameCount) { if (it % 63 > 10) 0.8 else 0.0 }
        val expected = mapOf(
            ("easy" to "auto") to (33 to "85c3cfb660afc999c14f66962825b8e58c54b19c723f9abc7ce3040a8c0d57a2"),
            ("normal" to "auto") to (67 to "ac6073dda2751a92ac038198899c9f2b1479f8ea64007f017925146416c17c53"),
            ("hard" to "auto") to (85 to "9bc05445e8e565070d9dc7c87f3c0af0778e0cc37d0af39ca953c3cd9c4759f9"),
            ("expert" to "auto") to (178 to "7d1ccb4f1a274a1d96db10d165c09fb47fa0b735c98e17d293f9d9abbc1e9a52"),
            ("master" to "auto") to (229 to "b4f60749a188d6bbeb37c876c6a640cd46debca1d3a9bb8578787b17ef814c8d"),
            ("master" to "2k") to (222 to "8de415755219b209de8204751eb217c1a1281e05baf0111a1451b3a4c0d1519c"),
            ("master" to "4k") to (228 to "f9499eb7c5e354f1b14fcce926d78f98a9cc6fb9840798d1ba530b01fbb3113e"),
        )
        for ((mode, golden) in expected) {
            val (tier, keyMode) = mode
            val result = Charter.generate(
                points, sections, 120.0, 480, bands, onset, rms, AutoChartConfig.SAMPLE_RATE,
                AutoChartOptions(tier = tier, keyMode = keyMode, seed = 42L, density = 1.15),
            )
            val columns = result.joinToString(";") { note ->
                val start = note.getValue("startTime").jsonArray.map { it.jsonPrimitive.int }
                val end = note.getValue("endTime").jsonArray.map { it.jsonPrimitive.int }
                val type = note.getValue("type").jsonPrimitive.int
                val lane = note.getValue("positionX").jsonPrimitive.double.toInt()
                (start + end + listOf(type, lane)).joinToString(",")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(columns.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals("$tier/$keyMode", golden, result.size to digest)
        }
    }
}
