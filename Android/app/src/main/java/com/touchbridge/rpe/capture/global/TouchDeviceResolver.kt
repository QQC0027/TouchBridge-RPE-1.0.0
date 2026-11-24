package com.touchbridge.rpe.capture.global

/** /dev/input 下的一个多点触摸设备。 */
data class TouchDevice(
    val path: String,
    val name: String,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val direct: Boolean,
)

/**
 * 解析 `getevent -pl` 输出，选出真正面向屏幕的多点触摸设备。
 *
 * 只认带 ABS_MT_POSITION_X/Y 的设备；优先 INPUT_PROP_DIRECT，
 * 没有 direct 标记时仅接受名称像触摸屏的设备，避免误选触摸板。
 */
object TouchDeviceResolver {

    private val EVENT_PATH = Regex("""/dev/input/event\d+""")
    private val ADD_DEVICE = Regex("""add device\s+\d+:\s*(/dev/input/event\d+)""")
    private val NAME = Regex("""name:\s*"([^"]*)"""")
    private const val NUMBER = """(0x[0-9a-fA-F]+|-?\d+)"""
    private val AXIS_X = Regex(
        """ABS_MT_POSITION_X\s*:\s*value\s+$NUMBER,\s*min\s+$NUMBER,\s*max\s+$NUMBER""",
    )
    private val AXIS_Y = Regex(
        """ABS_MT_POSITION_Y\s*:\s*value\s+$NUMBER,\s*min\s+$NUMBER,\s*max\s+$NUMBER""",
    )
    private val TOUCH_NAME = Regex("""touchscreen|touch|goodix|ft5|atmel|novatek|focal""", RegexOption.IGNORE_CASE)
    private val POINTER_DEVICE_NAME = Regex("""touchpad|trackpad|mouse""", RegexOption.IGNORE_CASE)

    /** 设备路径白名单；shell/root 命令拼接前必须校验。 */
    fun isValidPath(path: String): Boolean = EVENT_PATH.matches(path)

    fun parse(output: String): TouchDevice? {
        val candidates = candidates(output)
        return candidates.firstOrNull { it.direct }
            ?: candidates.firstOrNull { isTouchLike(it) }
    }

    fun candidates(output: String): List<TouchDevice> {
        val blocks = mutableListOf<String>()
        val builder = StringBuilder()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("add device")) {
                if (builder.isNotEmpty()) blocks += builder.toString()
                builder.setLength(0)
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
        }
        if (builder.isNotEmpty()) blocks += builder.toString()

        return blocks.mapNotNull { block ->
            runCatching { parseBlock(block) }.getOrNull()
        }.sortedByDescending { (it.maxX - it.minX).toLong() * (it.maxY - it.minY).toLong() }
    }

    private fun parseBlock(block: String): TouchDevice? {
        val path = ADD_DEVICE.find(block)?.groupValues?.get(1) ?: return null
        val name = NAME.find(block)?.groupValues?.get(1).orEmpty()
        val x = AXIS_X.find(block) ?: return null
        val y = AXIS_Y.find(block) ?: return null

        val minX = x.groupValues[2].parseAxis() ?: return null
        val maxX = x.groupValues[3].parseAxis() ?: return null
        val minY = y.groupValues[2].parseAxis() ?: return null
        val maxY = y.groupValues[3].parseAxis() ?: return null
        if (maxX <= minX || maxY <= minY) return null

        return TouchDevice(
            path = path,
            name = name,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            direct = block.contains("INPUT_PROP_DIRECT"),
        )
    }

    private fun isTouchLike(device: TouchDevice): Boolean =
        device.direct || (TOUCH_NAME.containsMatchIn(device.name) &&
            !POINTER_DEVICE_NAME.containsMatchIn(device.name))

    private fun String.parseAxis(): Int? = if (startsWith("0x", ignoreCase = true)) {
        substring(2).toIntOrNull(16)
    } else {
        toIntOrNull()
    }
}
