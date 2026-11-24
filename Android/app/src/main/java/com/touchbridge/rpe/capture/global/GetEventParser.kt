package com.touchbridge.rpe.capture.global

/**
 * 解析 `getevent -lt /dev/input/eventX` 文本流。
 *
 * 只在 SYN_REPORT 时合成一帧；slot/tracking id 的释放通过“从活动集合移除”
 * 表达，空帧用于让 RecordingSession 结算最后仍然按下的手指。
 */
class GetEventParser(
    private val device: TouchDevice,
    private val rotationProvider: () -> Int = { 0 },
    private val timeProvider: () -> Long = { 0L },
    private val onFrame: (NormalizedTouchFrame) -> Unit,
) {

    private data class Slot(
        var trackingId: Int = -1,
        var rawX: Int = 0,
        var rawY: Int = 0,
        var active: Boolean = false,
    )

    private val slots = HashMap<Int, Slot>()
    private var currentSlot = 0
    private var currentTimestampMs = 0L
    private var lastHadPointers = false
    private var releaseAllOnSyn = false

    fun consume(line: String) {
        if (line.isBlank()) return
        val parsed = parseLine(line) ?: return
        when (parsed.type) {
            TYPE_ABS -> handleAbs(parsed.code, parsed.value)
            TYPE_KEY -> if (parsed.code == "BTN_TOUCH" && parsed.value == 0L) {
                releaseAllOnSyn = true
            }
            TYPE_SYN -> when (parsed.code) {
                "SYN_REPORT" -> emitFrame()
                "SYN_DROPPED" -> {
                    // 内核事件丢失时无法可靠续接；主动发空帧结算，避免手指“粘住”。
                    val hadPointers = lastHadPointers
                    slots.clear()
                    releaseAllOnSyn = false
                    lastHadPointers = false
                    if (hadPointers) {
                        onFrame(NormalizedTouchFrame(emptyList(), currentTimestampMs.takeIf { it > 0 } ?: timeProvider()))
                    }
                }
            }
        }
    }

    private fun handleAbs(code: String, value: Long) {
        when (code) {
            "ABS_MT_SLOT" -> currentSlot = value.toInt()
            "ABS_MT_TRACKING_ID" -> {
                val slot = slots.getOrPut(currentSlot) { Slot() }
                if (value == -1L || value == 0xFFFF_FFFFL) {
                    slot.active = false
                    slot.trackingId = -1
                } else {
                    slot.trackingId = value.toInt()
                    slot.active = true
                }
            }
            "ABS_MT_POSITION_X" -> slots.getOrPut(currentSlot) { Slot() }.rawX = value.toInt()
            "ABS_MT_POSITION_Y" -> slots.getOrPut(currentSlot) { Slot() }.rawY = value.toInt()
            "ABS_X" -> slots.getOrPut(0) { Slot() }.rawX = value.toInt()
            "ABS_Y" -> slots.getOrPut(0) { Slot() }.rawY = value.toInt()
        }
    }

    private fun emitFrame() {
        if (releaseAllOnSyn) {
            slots.clear()
            releaseAllOnSyn = false
        }

        val points = slots.values
            .asSequence()
            .filter { it.active && it.trackingId >= 0 }
            .sortedBy { it.trackingId }
            .map { slot ->
                val x = normalize(slot.rawX, device.minX, device.maxX)
                val y = normalize(slot.rawY, device.minY, device.maxY)
                val (rx, ry) = rotate(x, y, rotationProvider())
                NormalizedTouchPoint(slot.trackingId, rx, ry)
            }
            .toList()

        if (points.isNotEmpty() || lastHadPointers) {
            lastHadPointers = points.isNotEmpty()
            onFrame(NormalizedTouchFrame(points, currentTimestampMs.takeIf { it > 0 } ?: timeProvider()))
        }
    }

    private fun parseLine(line: String): ParsedEvent? {
        var rest = line.trim()
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end > 1) {
                parseTimestamp(rest.substring(1, end))?.let { currentTimestampMs = it }
                rest = rest.substring(end + 1).trim()
            }
        }
        if (rest.isEmpty()) return null
        val tokens = rest.split(WHITESPACE)
        if (tokens.size < 3) return null
        val type = tokens[0]
        val code = tokens[1]
        val value = parseValue(tokens.last()) ?: return null
        return ParsedEvent(type, code, value)
    }

    private fun parseValue(token: String): Long? {
        if (token.equals("DOWN", ignoreCase = true)) return 1L
        if (token.equals("UP", ignoreCase = true)) return 0L
        val negative = token.startsWith("-")
        val unsigned = if (negative) token.substring(1) else token
        if (unsigned.startsWith("0x", ignoreCase = true)) {
            val value = unsigned.substring(2).toLongOrNull(16) ?: return null
            return if (negative) -value else value
        }
        // getevent 默认以十六进制打印数值；没有前缀时优先按十六进制解析。
        return token.toLongOrNull(16) ?: token.toLongOrNull(10)
    }

    private fun parseTimestamp(token: String): Long? {
        val clean = token.trim()
        val dot = clean.indexOf('.')
        return runCatching {
            if (dot < 0) {
                clean.toLong() * 1000L
            } else {
                val sec = clean.substring(0, dot).trim().toLong()
                val micros = clean.substring(dot + 1).trim().padEnd(6, '0').take(6).toLong()
                sec * 1000L + micros / 1000L
            }
        }.getOrNull()
    }

    private fun normalize(value: Int, min: Int, max: Int): Float {
        if (max <= min) return 0f
        return ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    }

    /** 触摸设备通常以自然方向（竖屏）上报，按 display rotation 映射到屏幕方向。 */
    private fun rotate(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        1 -> y to (1f - x)        // ROTATION_90（屏幕相对自然方向逆时针 90°）
        2 -> (1f - x) to (1f - y) // ROTATION_180
        3 -> (1f - y) to x        // ROTATION_270
        else -> x to y
    }

    private data class ParsedEvent(val type: String, val code: String, val value: Long)

    private companion object {
        const val TYPE_SYN = "EV_SYN"
        const val TYPE_KEY = "EV_KEY"
        const val TYPE_ABS = "EV_ABS"
        val WHITESPACE = Regex("""\s+""")
    }
}
