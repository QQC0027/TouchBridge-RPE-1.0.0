package com.touchbridge.rpe.capture

import android.os.SystemClock

/**
 * 后台录制的会话时钟：以录制开始时刻为 0，帧时间由 MotionEvent.eventTime
 * （与 SystemClock.uptimeMillis 同一基准）换算，保证与 View 采集同源。
 *
 * 深度睡眠期间不会有触摸事件；时长展示另用 [elapsedMs]。
 */
class SessionClock(
    private val startUptimeMs: Long = SystemClock.uptimeMillis(),
    val startedElapsedMs: Long = SystemClock.elapsedRealtime(),
) {

    fun timeAt(eventUptimeMs: Long): Double =
        (eventUptimeMs - startUptimeMs) / 1000.0

    fun now(): Double = (SystemClock.uptimeMillis() - startUptimeMs) / 1000.0

    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedElapsedMs
}
