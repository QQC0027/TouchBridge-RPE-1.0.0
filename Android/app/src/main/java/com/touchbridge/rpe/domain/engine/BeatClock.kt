package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.Beat
import com.touchbridge.rpe.domain.model.BpmSegment
import java.math.BigInteger

/**
 * 变速拍时钟：音乐秒 <-> 绝对拍数，支持 offset 与多段 BPM。
 * 逐行对应 engine.py 的 BeatClock。
 */
class BeatClock(
    bpm: Double = 120.0,
    offsetMs: Double = 0.0,
    val beatsPerBar: Int = 4,
    segments: List<BpmSegment>? = null,
) {
    val offsetSec: Double = offsetMs / 1000.0

    val segments: List<BpmSegment>

    private val times: DoubleArray

    init {
        val sorted = if (segments.isNullOrEmpty()) {
            listOf(BpmSegment(0.0, bpm))
        } else {
            segments.sortedBy { it.startBeat }
        }
        val normalized = if (sorted[0].startBeat != 0.0) {
            listOf(BpmSegment(0.0, sorted[0].bpm)) + sorted
        } else {
            sorted
        }
        this.segments = normalized
        this.times = DoubleArray(normalized.size)
        for (i in normalized.indices) {
            if (i == 0) {
                times[i] = 0.0
            } else {
                val prev = normalized[i - 1]
                val dt = (normalized[i].startBeat - prev.startBeat) * 60.0 / prev.bpm
                times[i] = times[i - 1] + dt
            }
        }
    }

    fun timeToBeat(musicTime: Double): Double {
        val ct = musicTime - offsetSec
        for (i in segments.indices.reversed()) {
            if (ct >= times[i] || i == 0) {
                val s = segments[i]
                return s.startBeat + (ct - times[i]) * s.bpm / 60.0
            }
        }
        return 0.0
    }

    fun beatToTime(beat: Double): Double {
        for (i in segments.indices.reversed()) {
            if (beat >= segments[i].startBeat || i == 0) {
                val s = segments[i]
                val ct = times[i] + (beat - s.startBeat) * 60.0 / s.bpm
                return ct + offsetSec
            }
        }
        return 0.0
    }

    /**
     * 绝对拍数 -> RPE Beat 三整数。Beat[0] 是整数拍（不是小节），
     * 分母必须为正整数，否则 RPE 会解析出 NaN。
     */
    fun beatToBbt(beat: Double): Beat {
        var f = BeatFraction.limitDenominator(beat, 1000)
        if (f.numerator.signum() < 0) f = BeatFraction.Fraction(BigInteger.ZERO, BigInteger.ONE)
        val whole = f.numerator.divide(f.denominator)
        val remNum = f.numerator.subtract(whole.multiply(f.denominator))
        return Beat(whole.toInt(), remNum.toInt(), f.denominator.toInt())
    }
}
