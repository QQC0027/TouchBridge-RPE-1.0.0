package com.touchbridge.rpe.capture.global

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetEventParserTest {

    private val device = TouchDevice(
        path = "/dev/input/event2",
        name = "test touch",
        minX = 0,
        maxX = 1000,
        minY = 0,
        maxY = 2000,
        direct = true,
    )

    @Test
    fun singlePointerNormalizesAndReleases() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 100.000000] EV_ABS       ABS_MT_TRACKING_ID   0000002a")
        parser.consume("[ 100.010000] EV_ABS       ABS_MT_POSITION_X    000001f4")
        parser.consume("[ 100.020000] EV_ABS       ABS_MT_POSITION_Y    000003e8")
        parser.consume("[ 100.030000] EV_SYN       SYN_REPORT           00000000")
        parser.consume("[ 100.040000] EV_ABS       ABS_MT_TRACKING_ID   ffffffff")
        parser.consume("[ 100.050000] EV_SYN       SYN_REPORT           00000000")

        assertEquals(2, frames.size)
        assertEquals(1, frames[0].pointers.size)
        assertEquals(42, frames[0].pointers[0].pointerId)
        assertEquals(0.5f, frames[0].pointers[0].x, 0.0001f)
        assertEquals(0.5f, frames[0].pointers[0].y, 0.0001f)
        assertEquals(100_030L, frames[0].timeMs)
        assertTrue(frames[1].pointers.isEmpty())
    }

    @Test
    fun multipleSlotsKeepDistinctPointers() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 1.000000] EV_ABS       ABS_MT_SLOT           00000000")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_TRACKING_ID   0000000a")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_POSITION_X    00000000")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_POSITION_Y    00000000")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_SLOT           00000001")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_TRACKING_ID   0000000b")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_POSITION_X    000003e8")
        parser.consume("[ 1.000000] EV_ABS       ABS_MT_POSITION_Y    000007d0")
        parser.consume("[ 1.000000] EV_SYN       SYN_REPORT           00000000")

        assertEquals(1, frames.size)
        assertEquals(listOf(10, 11), frames[0].pointers.map { it.pointerId })
        assertEquals(1f, frames[0].pointers[1].x, 0.0001f)
        assertEquals(1f, frames[0].pointers[1].y, 0.0001f)
    }

    @Test
    fun rotationMapsCoordinates() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(
            device = device,
            rotationProvider = { 1 },
            onFrame = { frames += it },
        )

        parser.consume("[ 2.000000] EV_ABS       ABS_MT_TRACKING_ID   00000001")
        parser.consume("[ 2.000000] EV_ABS       ABS_MT_POSITION_X    00000000")
        parser.consume("[ 2.000000] EV_ABS       ABS_MT_POSITION_Y    00000000")
        parser.consume("[ 2.000000] EV_SYN       SYN_REPORT           00000000")

        assertEquals(0f, frames.single().pointers.single().x, 0.0001f)
        assertEquals(1f, frames.single().pointers.single().y, 0.0001f)
    }

    @Test
    fun rotation270MapsCoordinates() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(
            device = device,
            rotationProvider = { 3 },
            onFrame = { frames += it },
        )

        parser.consume("[ 2.500000] EV_ABS       ABS_MT_TRACKING_ID   00000001")
        parser.consume("[ 2.500000] EV_ABS       ABS_MT_POSITION_X    000003e8")
        parser.consume("[ 2.500000] EV_ABS       ABS_MT_POSITION_Y    00000000")
        parser.consume("[ 2.500000] EV_SYN       SYN_REPORT           00000000")

        assertEquals(1f, frames.single().pointers.single().x, 0.0001f)
        assertEquals(1f, frames.single().pointers.single().y, 0.0001f)
    }

    @Test
    fun hexPrefixedValuesAreParsed() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 7.000000] EV_ABS       ABS_MT_TRACKING_ID   0x00000001")
        parser.consume("[ 7.000000] EV_ABS       ABS_MT_POSITION_X    0x000001f4")
        parser.consume("[ 7.000000] EV_ABS       ABS_MT_POSITION_Y    0x000003e8")
        parser.consume("[ 7.000000] EV_SYN       SYN_REPORT           0x00000000")
        parser.consume("[ 7.100000] EV_ABS       ABS_MT_TRACKING_ID   -0x1")
        parser.consume("[ 7.100000] EV_SYN       SYN_REPORT           0x00000000")

        assertEquals(1, frames[0].pointers.size)
        assertEquals(0.5f, frames[0].pointers.single().x, 0.0001f)
        assertEquals(0.5f, frames[0].pointers.single().y, 0.0001f)
        assertTrue(frames[1].pointers.isEmpty())
    }

    @Test
    fun buttonUpClearsAllPointers() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 3.000000] EV_ABS       ABS_MT_TRACKING_ID   00000001")
        parser.consume("[ 3.000000] EV_SYN       SYN_REPORT           00000000")
        parser.consume("[ 3.100000] EV_KEY       BTN_TOUCH            UP")
        parser.consume("[ 3.100000] EV_SYN       SYN_REPORT           00000000")

        assertEquals(1, frames.first().pointers.size)
        assertTrue(frames.last().pointers.isEmpty())
    }

    @Test
    fun noFrameUntilSynReport() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 5.000000] EV_ABS       ABS_MT_TRACKING_ID   00000001")
        parser.consume("[ 5.010000] EV_ABS       ABS_MT_POSITION_X    000001f4")
        assertTrue(frames.isEmpty())

        parser.consume("[ 5.020000] EV_SYN       SYN_REPORT           00000000")
        assertEquals(1, frames.size)
        assertEquals(1, frames.single().pointers.size)
    }

    @Test
    fun synDroppedReleasesActivePointers() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })

        parser.consume("[ 6.000000] EV_ABS       ABS_MT_TRACKING_ID   00000001")
        parser.consume("[ 6.000000] EV_SYN       SYN_REPORT           00000000")
        parser.consume("[ 6.010000] EV_SYN       SYN_DROPPED          00000000")

        assertEquals(2, frames.size)
        assertTrue(frames.last().pointers.isEmpty())
    }

    @Test
    fun malformedLineIsIgnored() {
        val frames = mutableListOf<NormalizedTouchFrame>()
        val parser = GetEventParser(device, onFrame = { frames += it })
        parser.consume("not a getevent line")
        parser.consume("[ 4.000000] EV_ABS ABS_MT_TRACKING_ID")
        assertTrue(frames.isEmpty())
    }
}
