package com.touchbridge.rpe.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.touchbridge.rpe.domain.model.TouchSample
import kotlin.math.min

/**
 * 全屏录制触摸面：把 MotionEvent（含历史帧）转成归一化触点帧。
 *
 * 只有“当前仍按下的 pointer”会被送出；采集层通过触点消失来判定抬手，
 * 因此 ACTION_POINTER_UP / ACTION_UP / ACTION_CANCEL 都必须送一帧空位。
 */
@SuppressLint("ClickableViewAccessibility")
class TouchPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onFrame: ((samples: List<TouchSample>, uptimeNanos: Long) -> Unit)? = null

    private val active = LinkedHashSet<Int>()
    private val ripples = ArrayList<Ripple>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#6BD6FA")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3AA0FF")
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        color = Color.parseColor("#223AA0FF")
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private class Ripple(var x: Float, var y: Float, val start: Long)

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active.clear()
                active.add(event.getPointerId(0))
                emit(event, event.eventTime * 1_000_000L)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                active.add(event.getPointerId(event.actionIndex))
                emit(event, event.eventTime * 1_000_000L)
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    emit(
                        event,
                        event.getHistoricalEventTime(h) * 1_000_000L,
                        historical = h,
                    )
                }
                emit(event, event.eventTime * 1_000_000L)
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                active.remove(event.getPointerId(event.actionIndex))
                emit(event, event.eventTime * 1_000_000L)
            }

            MotionEvent.ACTION_CANCEL -> {
                active.clear()
                emit(event, event.eventTime * 1_000_000L)
            }
        }
        return true
    }

    private fun emit(event: MotionEvent, uptimeNanos: Long, historical: Int = -1) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val samples = ArrayList<TouchSample>(active.size)
        val alive = ArrayList<Int>(active.size)
        for (id in active) {
            val idx = event.findPointerIndex(id)
            if (idx < 0) continue
            val px = if (historical >= 0 && historical < event.historySize) {
                event.getHistoricalX(idx, historical)
            } else {
                event.getX(idx)
            }
            val py = if (historical >= 0 && historical < event.historySize) {
                event.getHistoricalY(idx, historical)
            } else {
                event.getY(idx)
            }
            val size = if (historical >= 0 && historical < event.historySize) {
                event.getHistoricalSize(idx, historical)
            } else {
                event.getSize(idx)
            }
            val pressure = if (historical >= 0 && historical < event.historySize) {
                event.getHistoricalPressure(idx, historical)
            } else {
                event.getPressure(idx)
            }
            samples.add(
                TouchSample(
                    pointerId = id,
                    x = (px / w).toDouble().coerceIn(0.0, 1.0),
                    y = (py / h).toDouble().coerceIn(0.0, 1.0),
                    area = (if (size > 0f) size else pressure).toDouble().coerceIn(0.0, 1.0),
                    timeSec = uptimeNanos / 1e9,
                ),
            )
            alive.add(id)
            if (historical < 0) {
                ripples.removeAll { (now - it.start) > 900 }
                ripples.add(Ripple(px, py, now))
            }
        }
        onFrame?.invoke(samples, uptimeNanos)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = (now - r.start).toFloat()
            if (age > 900f) {
                it.remove()
                continue
            }
            val alpha = (1f - age / 900f)
            ripplePaint.color = Color.argb((alpha * 180).toInt(), 107, 214, 250)
            ripplePaint.alpha = (alpha * 180).toInt()
            canvas.drawCircle(r.x, r.y, dp(16f) + dp(40f) * alpha, ripplePaint)
        }
        for (r in ripples.takeLast(min(5, ripples.size))) {
            canvas.drawCircle(r.x, r.y, dp(26f), trailPaint)
            canvas.drawCircle(r.x, r.y, dp(7f), corePaint)
            canvas.drawCircle(r.x, r.y, dp(18f), ringPaint)
        }
        if (ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
