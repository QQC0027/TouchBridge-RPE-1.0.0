package com.touchbridge.rpe.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/** 手势类型，与 engine.py 中的 TAP/HOLD/FLICK/DRAG 一一对应。 */
enum class Gesture { TAP, HOLD, FLICK, DRAG }

/** 归一化触点（0~1，原点左上）。时间单位：秒（单调时钟）。 */
data class TouchSample(
    val pointerId: Int,
    val x: Double,
    val y: Double,
    val area: Double,
    val timeSec: Double,
)

/** 拖拽轨迹点：时间、归一化 x、归一化 y。 */
data class TrackPoint(val t: Double, val x: Double, val y: Double)

/** 手势引擎输出的“完成音符”，字段与 engine.py 的 completed 字典同构。 */
data class GestureNote(
    val gesture: Gesture,
    val finger: Int,
    val tSec: Double,
    val x: Double,
    val holdSec: Double = 0.0,
    val path: List<TrackPoint> = emptyList(),
    val dir: Pair<Double, Double>? = null,
    val gid: Int = 0,
)

/** RPE 的 Beat 三整数：beat = whole + num / den，den 必须为正整数。 */
data class Beat(val whole: Int, val num: Int, val den: Int) {
    fun toJsonArray(): List<Int> = listOf(whole, num, den)
    override fun toString(): String = "[$whole, $num, $den]"
}

/**
 * RPE 音符。startTime/endTime 为 Beat 三整数；startBeat/endBeat/dir/gid 仅内部
 * 预览与统计使用，导出时由 [com.touchbridge.rpe.domain.engine.RpeBuilder] 剔除。
 */
data class RpeNote(
    val type: Int,
    val startTime: Beat,
    val endTime: Beat,
    val positionX: Double,
    val above: Int = 1,
    val alpha: Int = 255,
    val size: Double = 1.0,
    val speed: Double = 1.0,
    val yOffset: Double = 0.0,
    val visibleTime: Double = 999999.0,
    val isFake: Int = 0,
    val startBeat: Double,
    val endBeat: Double,
    val dir: Pair<Double, Double>? = null,
    val gid: Int? = null,
) {
    fun gestureKind(): Gesture = when (type) {
        1 -> Gesture.TAP
        2 -> Gesture.HOLD
        3 -> Gesture.FLICK
        else -> Gesture.DRAG
    }
}

/** 变速段。 */
@kotlinx.serialization.Serializable
data class BpmSegment(val startBeat: Double, val bpm: Double)

/** 谱面元数据。 */
@kotlinx.serialization.Serializable
data class ChartMeta(
    val name: String = "我的谱面",
    val charter: String = "",
    val composer: String = "",
    val level: String = "",
    val id: String = "",
    val background: String = "",
    val song: String = "",
    val cover: String = "",
)

/** 与 Python round(x, digits) 语义一致（就近取偶）。 */
object Rounder {
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        return BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    /** Python round(x)（无 digits，返回整数，就近取偶）。 */
    fun roundToLong(value: Double): Long = Math.rint(value).toLong()

    /** Python round(x) 但结果为 Double，用于 int(round(x)) 后参与浮点运算。 */
    fun roundToDouble(value: Double): Double = Math.rint(value)
}
