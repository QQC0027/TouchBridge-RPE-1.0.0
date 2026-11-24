package com.touchbridge.rpe.capture

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent

/**
 * 过滤落在悬浮按钮上的触摸，避免把“点开始录制”本身记成一次玩家操作。
 * 悬浮窗所在的窗口会消费触摸，但无障碍层仍可能观测到同一事件。
 */
object OverlayTouchGuard {

    private const val GUARD_MS = 500L
    private const val SLOP_PX = 12f

    @Volatile
    private var bounds: Rect? = null

    @Volatile
    private var guardedUntilMs: Long = 0L

    @Volatile
    private var screenWidth: Int = 0

    @Volatile
    private var screenHeight: Int = 0

    fun updateBounds(rect: Rect?) {
        bounds = rect?.let { Rect(it) }
    }

    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    /**
     * /dev/input 后端没有 MotionEvent 的时间窗口，只能按屏幕区域过滤；
     * x/y 为 0f..1f 的屏幕归一化坐标。
     */
    fun isOverlayPointNormalized(x: Float, y: Float): Boolean {
        val rect = bounds ?: return false
        if (screenWidth <= 0 || screenHeight <= 0) return false
        return isOverlayPoint(x * screenWidth, y * screenHeight)
    }

    /** 原始屏幕像素坐标是否落在悬浮条内。 */
    fun isOverlayPoint(px: Float, py: Float): Boolean {
        val rect = bounds ?: return false
        return px >= rect.left - SLOP_PX && px <= rect.right + SLOP_PX &&
            py >= rect.top - SLOP_PX && py <= rect.bottom + SLOP_PX
    }

    /** 悬浮按钮处理触摸时调用，额外延长保护窗口。 */
    fun notifyTouchHandled() {
        guardedUntilMs = SystemClock.uptimeMillis() + GUARD_MS
    }

    fun isOverlayTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        val rect = bounds ?: return false
        if (SystemClock.uptimeMillis() > guardedUntilMs) return false
        if (width <= 0 || height <= 0) return false
        for (index in 0 until event.pointerCount) {
            val x = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                event.getRawX(index)
            } else {
                event.getX(index)
            }
            val y = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                event.getRawY(index)
            } else {
                event.getY(index)
            }
            if (x >= rect.left - SLOP_PX && x <= rect.right + SLOP_PX &&
                y >= rect.top - SLOP_PX && y <= rect.bottom + SLOP_PX
            ) {
                return true
            }
        }
        return false
    }
}
