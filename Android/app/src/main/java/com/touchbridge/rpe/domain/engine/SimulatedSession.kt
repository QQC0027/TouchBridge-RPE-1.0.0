package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TrackPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 对应 engine.py 的 simulate_session()：生成示例手势用于自测与演示。 */
object SimulatedSession {

    data class Result(val notes: List<GestureNote>, val clock: BeatClock)

    fun generate(
        durationSec: Double = 12.0,
        bpm: Double = 120.0,
        offsetMs: Double = 0.0,
        seed: Long = 42L,
    ): Result {
        val rnd = Random(seed)
        val clock = BeatClock(bpm = bpm, offsetMs = offsetMs)
        val notes = mutableListOf<GestureNote>()
        var t = 1.0
        var finger = 0
        while (t < durationSec) {
            val r = rnd.nextDouble()
            val x = rnd.nextDouble()
            when {
                r < 0.55 -> {
                    notes.add(GestureNote(Gesture.TAP, finger % 5, t, x, gid = notes.size))
                    t += 0.25 + rnd.nextDouble() * 0.25
                }

                r < 0.78 -> {
                    val dur = 0.3 + rnd.nextDouble() * 0.5
                    notes.add(GestureNote(Gesture.HOLD, finger % 5, t, x, holdSec = dur, gid = notes.size))
                    t += dur + 0.2
                }

                r < 0.9 -> {
                    val dur = 0.08 + rnd.nextDouble() * 0.06
                    val nx = min(1.0, x + 0.25)
                    notes.add(
                        GestureNote(
                            gesture = Gesture.FLICK,
                            finger = finger % 5,
                            tSec = t + dur,
                            x = nx,
                            path = listOf(TrackPoint(t, x, 0.5), TrackPoint(t + dur, nx, 0.5)),
                            dir = Pair(1.0, 0.0),
                            gid = notes.size,
                        ),
                    )
                    t += 0.3
                }

                else -> {
                    val path = mutableListOf<TrackPoint>()
                    var cx = x
                    for (k in 0 until 8) {
                        cx = max(0.0, min(1.0, cx + (rnd.nextDouble() - 0.5) * 0.3))
                        path.add(TrackPoint(t + k * 0.05, cx, 0.5))
                    }
                    notes.add(
                        GestureNote(
                            gesture = Gesture.DRAG,
                            finger = finger % 5,
                            tSec = t,
                            x = x,
                            path = path,
                            gid = notes.size,
                        ),
                    )
                    t += 0.5
                }
            }
            finger++
        }
        return Result(notes, clock)
    }
}
