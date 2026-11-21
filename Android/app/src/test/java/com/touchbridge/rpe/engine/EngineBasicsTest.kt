package com.touchbridge.rpe.engine

import com.touchbridge.rpe.domain.engine.BeatClock
import com.touchbridge.rpe.domain.engine.NoteConverter
import com.touchbridge.rpe.domain.engine.Quantizer
import com.touchbridge.rpe.domain.model.BpmSegment
import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBasicsTest {

    @Test
    fun `beat to bbt keeps positive denominator`() {
        val clock = BeatClock(bpm = 120.0)
        assertEquals(listOf(1, 0, 1), clock.beatToBbt(1.0).toJsonArray())
        assertEquals(listOf(1, 1, 2), clock.beatToBbt(1.5).toJsonArray())
        assertEquals(listOf(0, 1, 3), clock.beatToBbt(1.0 / 3.0).toJsonArray())
        val free = clock.beatToBbt(3.3333333)
        assertEquals(3, free.whole)
        assertTrue("denominator must be positive", free.den > 0)
    }

    @Test
    fun `offset shifts music time`() {
        val clock = BeatClock(bpm = 120.0, offsetMs = 500.0)
        assertEquals(0.0, clock.timeToBeat(0.5), 1e-9)
        assertEquals(1.0, clock.timeToBeat(1.0), 1e-9)
        assertEquals(0.5, clock.beatToTime(0.0), 1e-9)
    }

    @Test
    fun `multi segment bpm conversion`() {
        val clock = BeatClock(
            bpm = 120.0,
            segments = listOf(BpmSegment(0.0, 120.0), BpmSegment(4.0, 240.0)),
        )
        assertEquals(4.0, clock.timeToBeat(2.0), 1e-9)
        assertEquals(2.5, clock.beatToTime(6.0), 1e-9)
        assertEquals(8.0, clock.timeToBeat(3.0), 1e-9)
    }

    @Test
    fun `quantize snaps inside tolerance only`() {
        assertEquals(1.0, Quantizer.quantize(1.03, 8, 0.6), 1e-9)
        assertEquals(1.125, Quantizer.quantize(1.1, 8, 0.6), 1e-9)
        assertEquals(1.1, Quantizer.quantize(1.1, 8, 0.0), 1e-9)
    }

    private fun settings(): ChartSettings = ChartSettings(
        bpm = 120.0,
        subdivision = 8,
        snapTolerance = 0.6,
        debounceMs = 40.0,
    )

    @Test
    fun `tap uses down time and maps x`() {
        val clock = BeatClock(bpm = 120.0)
        val notes = NoteConverter.convert(
            listOf(GestureNote(Gesture.TAP, finger = 0, tSec = 0.5, x = 0.5)),
            clock,
            settings(),
        )
        assertEquals(1, notes.size)
        assertEquals(1, notes[0].type)
        assertEquals(1.0, notes[0].startBeat, 1e-9)
        assertEquals(0.0, notes[0].positionX, 1e-9)
    }

    @Test
    fun `debounce drops same finger notes within window`() {
        val clock = BeatClock(bpm = 120.0)
        val notes = NoteConverter.convert(
            listOf(
                GestureNote(Gesture.TAP, finger = 2, tSec = 0.5, x = 0.4),
                GestureNote(Gesture.TAP, finger = 2, tSec = 0.52, x = 0.7),
            ),
            clock,
            settings(),
        )
        assertEquals(1, notes.size)
    }

    @Test
    fun `hold start and end are quantized with minimum grid`() {
        val clock = BeatClock(bpm = 120.0)
        val notes = NoteConverter.convert(
            listOf(
                GestureNote(Gesture.HOLD, finger = 0, tSec = 1.0, x = 0.2, holdSec = 1.0),
                GestureNote(Gesture.HOLD, finger = 1, tSec = 4.0, x = 0.6, holdSec = 0.01),
            ),
            clock,
            settings(),
        )
        assertEquals(2, notes.size)
        assertTrue(notes.all { it.type == 2 })
        assertEquals(2.0, notes[0].startBeat, 1e-9)
        assertEquals(4.0, notes[0].endBeat, 1e-9)
        assertEquals(8.0, notes[1].startBeat, 1e-9)
        assertTrue("min length must be at least one grid", notes[1].endBeat > notes[1].startBeat)
    }

    @Test
    fun `drag path is downsampled and keeps gid`() {
        val clock = BeatClock(bpm = 120.0)
        val path = (0..8).map { i -> TrackPoint(i * 0.05, i * 0.1, 0.5) }
        val notes = NoteConverter.convert(
            listOf(GestureNote(Gesture.DRAG, finger = 0, tSec = 0.0, x = 0.0, path = path, gid = 5)),
            clock,
            ChartSettings(bpm = 120.0, subdivision = 8, dragSampleMs = 25.0, snapTolerance = 0.0),
        )
        assertTrue("drag should yield multiple notes", notes.size >= 2)
        assertTrue(notes.all { it.type == 4 && it.gid == 5 })
    }

    @Test
    fun `holds are sorted after non hold notes`() {
        val clock = BeatClock(bpm = 120.0)
        val notes = NoteConverter.convert(
            listOf(
                GestureNote(Gesture.HOLD, finger = 0, tSec = 0.0, x = 0.1, holdSec = 0.5),
                GestureNote(Gesture.TAP, finger = 1, tSec = 0.0, x = 0.9),
            ),
            clock,
            settings(),
        )
        assertEquals(1, notes[0].type)
        assertEquals(2, notes[1].type)
    }
}
