package com.touchbridge.rpe.capture.global

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchDeviceResolverTest {

    private val output = """
        add device 1: /dev/input/event0
          name: "gpio-keys"
          events:
            KEY (0001): KEY_VOLUMEUP
        add device 2: /dev/input/event3
          name: "Synaptics Touchpad"
          events:
            ABS (0003): ABS_X : value 0, min 0, max 1000
                        ABS_Y : value 0, min 0, max 1000
        add device 3: /dev/input/event5
          name: "fts_ts"
          events:
            ABS (0003): ABS_MT_SLOT : value 0, min 0, max 9
                        ABS_MT_POSITION_X : value 0, min 0, max 1079
                        ABS_MT_POSITION_Y : value 0, min 0, max 2399
            INPUT_PROP_DIRECT (0004)
    """.trimIndent()

    @Test
    fun selectsDirectTouchscreenAndIgnoresTouchpad() {
        val device = TouchDeviceResolver.parse(output)
        assertEquals("/dev/input/event5", device?.path)
        assertEquals(1079, device?.maxX)
        assertEquals(2399, device?.maxY)
    }

    @Test
    fun rejectsOutputWithoutMultitouchAxes() {
        assertNull(
            TouchDeviceResolver.parse(
                """
                add device 1: /dev/input/event0
                  name: "gpio-keys"
                  events:
                    KEY (0001): KEY_VOLUMEUP
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun touchpadNameIsNotFallbackWhenNoDirectProperty() {
        val touchpad = """
            add device 1: /dev/input/event9
              name: "Synaptics Touchpad"
              events:
                ABS (0003): ABS_MT_POSITION_X : value 0, min 0, max 1000
                            ABS_MT_POSITION_Y : value 0, min 0, max 1000
                INPUT_PROP_POINTER (0004)
        """.trimIndent()
        assertNull(TouchDeviceResolver.parse(touchpad))
    }

    @Test
    fun parsesHexAxisRanges() {
        val hex = """
            add device 1: /dev/input/event7
              name: "touchscreen"
              events:
                ABS (0003): ABS_MT_POSITION_X : value 0, min 0x0, max 0x43f
                            ABS_MT_POSITION_Y : value 0, min 0x0, max 0x95f
                INPUT_PROP_DIRECT (0004)
        """.trimIndent()
        val device = TouchDeviceResolver.parse(hex)
        assertEquals(0x43f, device?.maxX)
        assertEquals(0x95f, device?.maxY)
    }

    @Test
    fun returnsOnlyMultitouchCandidatesLargestFirst() {
        val candidates = TouchDeviceResolver.candidates(output)
        assertEquals(1, candidates.size)
        assertEquals("/dev/input/event5", candidates.first().path)
    }
}
