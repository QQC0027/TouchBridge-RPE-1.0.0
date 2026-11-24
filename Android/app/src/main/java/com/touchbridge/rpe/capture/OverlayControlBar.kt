package com.touchbridge.rpe.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.edit
import androidx.core.view.doOnLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 右上角小型悬浮控制条。
 *
 * ARMED：开始录制 + 返回；RECORDING：结束录制 + 计时（返回按钮真正移除）；
 * DEGRADED：结束录制 + 中断提示；BUSY/ERROR 只显示状态。
 * 窗口本身不抢占焦点，仅消费按钮区域内的触摸。
 */
@SuppressLint("ViewConstructor")
class OverlayControlBar(
    context: Context,
    private val prefs: SharedPreferences,
) : View(context) {

    enum class Mode { BUSY, ARMED, RECORDING, DEGRADED, ERROR }

    interface Listener {
        fun onStartRequested()
        fun onStopRequested()
        fun onBackRequested()
    }

    var listener: Listener? = null

    var mode: Mode = Mode.BUSY
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
            post { refreshWindowSize() }
        }

    var elapsedMs: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                if (mode == Mode.RECORDING || mode == Mode.DEGRADED) invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val marginPx = dp(10f).roundToInt()
    private val buttonHeightPx = dp(42f).roundToInt()
    private val compactHeightPx = dp(46f).roundToInt()
    private val recordHeightPx = dp(58f).roundToInt()
    private val gapPx = dp(6f).roundToInt()
    private val sidePaddingPx = dp(6f).roundToInt()
    private val barWidthPx = dp(210f).roundToInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val buttonRect = RectF()

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private var touchActive = false
    private var placedOnce = false

    init {
        isClickable = true
        contentDescription = "悬浮录制控制条"
        // ARMED -> RECORDING 会改变高度；布局变化后必须同步过滤区域。
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (placedOnce) publishBounds()
        }
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
            x = prefs.getInt(KEY_X, -1).takeIf { it >= 0 } ?: 0
            y = prefs.getInt(KEY_Y, -1).takeIf { it >= 0 } ?: 0
        }
        params = layoutParams
        val saved = prefs.contains(KEY_X) && prefs.contains(KEY_Y)
        val added = runCatching { manager.addView(this, layoutParams) }.isSuccess
        if (added) {
            // addView 之后立即读取 measuredWidth 会是 0；等真实布局完成再吸顶/吸边。
            doOnLayout {
                if (!placedOnce) {
                    if (saved) clampToScreen() else placeTopRight()
                    placedOnce = true
                }
                publishBounds()
            }
        }
    }

    fun onDisplayChanged() {
        post {
            if (!placedOnce) return@post
            clampToScreen()
            publishBounds()
        }
    }

    fun detach() {
        placedOnce = false
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
        OverlayTouchGuard.updateBounds(null)
    }

    private fun placeTopRight() {
        val layoutParams = params ?: return
        val screen = screenSize()
        layoutParams.x = (screen.x - measuredWidth - marginPx).coerceAtLeast(0)
        layoutParams.y = (statusBarHeight() + dp(8f).roundToInt()).coerceAtLeast(0)
        updateWindow()
    }

    private fun clampToScreen() {
        val layoutParams = params ?: return
        val screen = screenSize()
        layoutParams.x = layoutParams.x.coerceIn(0, maxOf(0, screen.x - measuredWidth))
        layoutParams.y = layoutParams.y.coerceIn(0, maxOf(0, screen.y - measuredHeight))
        updateWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(barWidthPx, if (mode == Mode.RECORDING || mode == Mode.DEGRADED) {
            recordHeightPx
        } else {
            compactHeightPx
        })
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(14f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bgPaint.color = BG_COLOR
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        when (mode) {
            Mode.ARMED -> drawArmed(canvas)
            Mode.RECORDING -> drawAction(canvas, "结束录制", STOP_COLOR, formatElapsed(), "REC")
            Mode.DEGRADED -> drawAction(
                canvas,
                "结束录制",
                DEGRADED_COLOR,
                formatElapsed(),
                "采集中断",
            )
            Mode.BUSY -> drawCenterText(canvas, "正在准备采集，点此返回", textPaint)
            Mode.ERROR -> drawCenterText(canvas, "采集不可用，点此返回", textPaint)
        }
    }

    private fun drawArmed(canvas: Canvas) {
        val outer = dp(5f)
        val buttonWidth = (width - outer * 2 - gapPx) / 2f
        val top = (height - buttonHeightPx) / 2f
        val bottom = top + buttonHeightPx
        val r = buttonHeightPx / 2f

        buttonRect.set(outer, top, outer + buttonWidth, bottom)
        buttonPaint.color = START_COLOR
        canvas.drawRoundRect(buttonRect, r, r, buttonPaint)
        canvas.drawText("开始录制", buttonRect.centerX(), buttonRect.centerY() + textOffset(), textPaint)

        buttonRect.set(outer + buttonWidth + gapPx, top, width - outer, bottom)
        buttonPaint.color = BACK_COLOR
        canvas.drawRoundRect(buttonRect, r, r, buttonPaint)
        canvas.drawText("返回", buttonRect.centerX(), buttonRect.centerY() + textOffset(), textPaint)
    }

    private fun drawAction(
        canvas: Canvas,
        label: String,
        color: Int,
        elapsed: String,
        prefix: String,
    ) {
        val outer = dp(5f)
        val top = outer
        buttonRect.set(outer, top, width - outer, top + buttonHeightPx)
        buttonPaint.color = color
        canvas.drawRoundRect(buttonRect, buttonHeightPx / 2f, buttonHeightPx / 2f, buttonPaint)
        canvas.drawText(label, buttonRect.centerX(), buttonRect.centerY() + textOffset(), textPaint)

        val subY = buttonRect.bottom + dp(10f)
        subPaint.color = if (color == DEGRADED_COLOR) Color.rgb(255, 224, 178) else Color.WHITE
        canvas.drawText("$prefix  $elapsed", width / 2f, subY, subPaint)
    }

    private fun drawCenterText(canvas: Canvas, label: String, paint: Paint) {
        canvas.drawText(label, width / 2f, height / 2f + textOffset(), paint)
    }

    private fun textOffset(): Float = (textPaint.textSize / 3f)

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
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                if (dragging) moveTo(originX + dx.toInt(), originY + dy.toInt())
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                touchActive = false
                if (dragging) {
                    dragging = false
                    snapToEdge()
                    persistPosition()
                } else {
                    OverlayTouchGuard.notifyTouchHandled()
                    performClick()
                    // 悬浮窗的 View left/top 恒为 0，必须用事件局部坐标判定按钮。
                    dispatchTap(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dispatchTap(localX: Float, localY: Float) {
        if (localY < 0f || localY > height) return
        when (mode) {
            Mode.ARMED -> {
                val outer = dp(5f)
                val buttonWidth = (width - outer * 2 - gapPx) / 2f
                if (localX <= outer + buttonWidth) {
                    listener?.onStartRequested()
                } else {
                    listener?.onBackRequested()
                }
            }
            Mode.RECORDING, Mode.DEGRADED -> listener?.onStopRequested()
            // 准备阶段可能因 Root 授权弹窗等待较久，必须允许用户取消返回。
            Mode.ERROR, Mode.BUSY -> listener?.onBackRequested()
        }
    }

    private fun moveTo(x: Int, y: Int) {
        val layoutParams = params ?: return
        val screen = screenSize()
        layoutParams.x = x.coerceIn(0, maxOf(0, screen.x - width))
        layoutParams.y = y.coerceIn(0, maxOf(0, screen.y - height))
        updateWindow()
    }

    private fun snapToEdge() {
        val layoutParams = params ?: return
        val screen = screenSize()
        layoutParams.x = if (layoutParams.x + width / 2 < screen.x / 2) {
            marginPx
        } else {
            maxOf(marginPx, screen.x - width - marginPx)
        }
        layoutParams.y = layoutParams.y.coerceIn(0, maxOf(0, screen.y - height))
        updateWindow()
    }

    private fun updateWindow() {
        val layoutParams = params ?: return
        val manager = windowManager ?: return
        runCatching { manager.updateViewLayout(this, layoutParams) }
        publishBounds()
    }

    private fun refreshWindowSize() {
        if (!placedOnce) return
        updateWindow()
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
        val screen = screenSize()
        val bounds = Rect(
            layoutParams.x,
            layoutParams.y,
            layoutParams.x + width,
            layoutParams.y + height,
        )
        OverlayTouchGuard.updateScreenSize(screen.x, screen.y)
        OverlayTouchGuard.updateBounds(bounds)
    }

    private fun screenSize(): Point {
        val manager = windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = manager.maximumWindowMetrics.bounds
                    return Point(bounds.width(), bounds.height())
                }
                @Suppress("DEPRECATION")
                return Point().also { manager.defaultDisplay.getRealSize(it) }
            }
        }
        return Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun statusBarHeight(): Int = runCatching {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else dp(24f).roundToInt()
    }.getOrDefault(dp(24f).roundToInt())

    private fun formatElapsed(): String {
        val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun dp(value: Float): Float = value * density

    companion object {
        private const val KEY_X = "control_bar_x"
        private const val KEY_Y = "control_bar_y"

        private val BG_COLOR = Color.parseColor("#E6101824")
        private val START_COLOR = Color.parseColor("#E61F6FEB")
        private val BACK_COLOR = Color.parseColor("#E624324A")
        private val STOP_COLOR = Color.parseColor("#E6E5484D")
        private val DEGRADED_COLOR = Color.parseColor("#E6B45309")
    }
}
