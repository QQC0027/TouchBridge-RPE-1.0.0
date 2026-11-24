package com.touchbridge.rpe.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 后台录制悬浮按钮：单击开始/停止，长按（或拖动超过阈值）进入拖动，
 * 松手吸附到就近屏幕侧边并记忆位置。无动画、无定时重绘，控制功耗。
 */
@SuppressLint("ViewConstructor")
class OverlayRecordButton(
    context: Context,
    private val prefs: SharedPreferences,
) : View(context) {

    interface Listener {
        fun onToggleRequested()
        fun onPositionChanged(rect: android.graphics.Rect)
    }

    var listener: Listener? = null

    var recording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var degraded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var label: String = "点击开始录制"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val widthPx = dp(132f).roundToInt()
    private val heightPx = dp(52f).roundToInt()
    private val marginPx = dp(12f).roundToInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private var touchActive = false

    private val longPressRunnable = Runnable {
        if (!touchActive) return@Runnable
        dragging = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        isClickable = true
        contentDescription = "后台录制悬浮按钮"
    }

    fun attach(manager: WindowManager) {
        windowManager = manager
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0).takeIf { it > 0 } ?: defaultX()
            y = prefs.getInt(KEY_Y, 0).takeIf { it > 0 } ?: defaultY()
        }
        params = layoutParams
        runCatching { manager.addView(this, layoutParams) }
        publishBounds()
    }

    fun detach() {
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
        OverlayTouchGuard.updateBounds(null)
    }

    private fun defaultX(): Int = screenWidth() - widthPx - marginPx

    private fun defaultY(): Int = screenHeight() - heightPx - dp(96f).roundToInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(widthPx, heightPx)
    }

    private val rect = RectF()
    private val colorDegraded = Color.parseColor("#E6B45309")
    private val colorRecording = Color.parseColor("#E6E5484D")
    private val colorIdle = Color.parseColor("#E6101824")

    override fun onDraw(canvas: Canvas) {
        val radius = heightPx / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bgPaint.color = when {
            degraded -> colorDegraded
            recording -> colorRecording
            else -> colorIdle
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val centerY = if (recording) height / 2f - dp(6f) else height / 2f
        canvas.drawText(label, width / 2f, centerY + dp(4f), textPaint)
        if (recording) {
            val seconds = ((SystemClock.elapsedRealtime() - sessionStart) / 1000L).coerceAtLeast(0L)
            val text = "REC %02d:%02d".format(seconds / 60, seconds % 60)
            canvas.drawText(text, width / 2f, height / 2f + dp(14f), subPaint)
        }
    }

    var sessionStart: Long = 0L
        set(value) {
            field = value
            if (recording) invalidate()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                originX = params?.x ?: 0
                originY = params?.y ?: 0
                OverlayTouchGuard.notifyTouchHandled()
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    moveTo(originX + dx.toInt(), originY + dy.toInt())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                touchActive = false
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    snapToEdge()
                    persistPosition()
                    dragging = false
                } else {
                    OverlayTouchGuard.notifyTouchHandled()
                    performClick()
                    listener?.onToggleRequested()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                dragging = false
                removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveTo(x: Int, y: Int) {
        val layoutParams = params ?: return
        val manager = windowManager ?: return
        layoutParams.x = x.coerceIn(0, maxOf(0, screenWidth() - widthPx))
        layoutParams.y = y.coerceIn(0, maxOf(0, screenHeight() - heightPx))
        runCatching { manager.updateViewLayout(this, layoutParams) }
        publishBounds()
    }

    private fun snapToEdge() {
        val layoutParams = params ?: return
        val maxX = maxOf(0, screenWidth() - widthPx)
        layoutParams.x = if (layoutParams.x + widthPx / 2 < screenWidth() / 2) marginPx else maxX - marginPx
        windowManager?.let { runCatching { it.updateViewLayout(this, layoutParams) } }
        publishBounds()
    }

    private fun persistPosition() {
        val layoutParams = params ?: return
        prefs.edit {
            putInt(KEY_X, layoutParams.x)
            putInt(KEY_Y, layoutParams.y)
        }
    }

    private fun publishBounds() {
        val layoutParams = params ?: return
        val rect = android.graphics.Rect(
            layoutParams.x,
            layoutParams.y,
            layoutParams.x + widthPx,
            layoutParams.y + heightPx,
        )
        OverlayTouchGuard.updateBounds(rect)
        listener?.onPositionChanged(rect)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val LONG_PRESS_MS = 400L
    }
}
