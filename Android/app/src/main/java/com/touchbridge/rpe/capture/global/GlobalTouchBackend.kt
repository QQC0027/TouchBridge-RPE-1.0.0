package com.touchbridge.rpe.capture.global

import com.touchbridge.rpe.capture.CaptureSource

/** 全局触摸采集后端：无障碍不实现该接口，由系统服务直接转发。 */
interface GlobalTouchBackend {
    val id: String
    val source: CaptureSource

    /** 必须实际执行一次探针；没有读取权限时返回 false。 */
    suspend fun isAvailable(): Boolean

    fun start(
        onFrame: (NormalizedTouchFrame) -> Unit,
        onError: (Throwable) -> Unit,
    )

    fun stop()
}

/**
 * 已归一化到屏幕方向的一帧触摸。
 *
 * [NormalizedTouchPoint.x]/[y] 取值 0f..1f；[timeMs] 使用 uptime 毫秒，
 * 与 [com.touchbridge.rpe.capture.SessionClock] 的基准一致。
 */
data class NormalizedTouchFrame(
    val pointers: List<NormalizedTouchPoint>,
    val timeMs: Long,
)

data class NormalizedTouchPoint(
    val pointerId: Int,
    val x: Float,
    val y: Float,
)
