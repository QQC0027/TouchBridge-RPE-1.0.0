package com.touchbridge.rpe.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.touchbridge.rpe.domain.model.TouchSample
import java.util.concurrent.Executor

/**
 * 全局触摸采集：仅在后台录制期间请求触摸探索模式并转发 MotionEvent。
 *
 * 注意：Android 公开 API 要求服务请求触摸探索模式（FLAG_REQUEST_TOUCH_EXPLORATION_MODE）
 * 才会收到全屏 MotionEvent；该模式可能改变单指点击行为，需在真机 Gate 中验证。
 */
class GlobalTouchAccessibilityService : AccessibilityService() {

    private var controller: TouchInteractionController? = null
    private var controllerCallback: TouchInteractionController.Callback? = null
    private var captureRequested = false
    private var lastSignature = 0L
    private var lastSignatureAt = 0L
    private var displayWidth = 0
    private var displayHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalCaptureBridge.attach(this)
        refreshDisplaySize()
        applyCaptureRequest(GlobalCaptureBridge.isRequested)
        // API 33 依赖 TouchInteractionController；34+ 直接走 onMotionEvent，
        // 同时注册两路会造成重复帧。
        val controllerReady = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            registerTouchController()
        } else {
            true
        }
        val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && controllerReady
        GlobalCaptureBridge.markAvailable(available)
        RecordingCoordinator.setAccessibilityAvailable(available)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplaySize()
        if (displayWidth > 0 && displayHeight > 0) {
            RecordingCoordinator.updateDisplayInfo(
                DisplayInfo(
                    widthPx = displayWidth,
                    heightPx = displayHeight,
                    densityDpi = resources.displayMetrics.densityDpi,
                    rotation = displayRotation(),
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = runCatching {
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(0)

    override fun onInterrupt() {
        // 反馈被系统打断不影响采集；真正的断连走 onUnbind。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** 只在后台录制期间请求触摸探索，停止后立即撤销，减少对整机交互的影响。 */
    fun applyCaptureRequest(active: Boolean) {
        captureRequested = active
        val base = serviceInfo ?: return
        val exploration = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        // Android 14+ 的 onMotionEvent 不需要触摸探索；继续请求会让单指点击
        // 被系统解释为探索/聚焦，改变游戏内操作。
        var flags = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            if (active) base.flags or exploration else base.flags and exploration.inv()
        } else {
            base.flags and exploration.inv()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = if (active) {
                flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
            } else {
                flags and AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS.inv()
            }
        }
        serviceInfo = base.apply {
            this.flags = flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && active) {
                motionEventSources = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerTouchController(): Boolean {
        unregisterController()
        return runCatching {
            val target = getTouchInteractionController(Display.DEFAULT_DISPLAY) ?: return false
            val callback = createTouchCallback() ?: return false
            val executor: Executor = ContextCompat.getMainExecutor(this)
            target.registerCallback(executor, callback)
            controller = target
            controllerCallback = callback
            true
        }.getOrDefault(false)
    }

    /**
     * TouchInteractionController.Callback 的抽象方法在不同 API 版本不一致
     * （API 33 的 onStateChanged 带 controller 参数），用动态代理实现运行时接口，
     * 只处理 onMotionEvent，避免签名差异引发 AbstractMethodError。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createTouchCallback(): TouchInteractionController.Callback? = runCatching {
        val type = TouchInteractionController.Callback::class.java
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, args ->
            if (method.name == "onMotionEvent" && args?.isNotEmpty() == true) {
                (args[0] as? MotionEvent)?.let(::forward)
            }
            null
        }
        proxy as TouchInteractionController.Callback
    }.getOrNull()

    /** AccessibilityService.onMotionEvent 自 Android 14（API 34）起可用。 */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onMotionEvent(event: MotionEvent) {
        forward(event)
    }

    private fun forward(event: MotionEvent) {
        if (!captureRequested || !GlobalCaptureBridge.isRequested) return
        if (displayWidth <= 0 || displayHeight <= 0) refreshDisplaySize()
        if (displayWidth <= 0 || displayHeight <= 0) return
        if (isDuplicate(event)) return
        val samples = event.toSamples(displayWidth.toDouble(), displayHeight.toDouble())
        val w = displayWidth.toFloat()
        val h = displayHeight.toFloat()
        val filtered = samples.filterNot {
            OverlayTouchGuard.isOverlayPoint(it.x.toFloat() * w, it.y.toFloat() * h)
        }
        // 整帧都落在悬浮条上时直接丢弃；但“真实的全部抬起”（samples 为空）必须保留。
        if (samples.isNotEmpty() && filtered.isEmpty()) return
        RecordingCoordinator.submitGlobalFrame(filtered, event.eventTime)
    }

    /**
     * onMotionEvent 与 TouchInteractionController 可能同时投递同一事件，
     * 用事件签名做一次廉价去重。
     */
    private fun isDuplicate(event: MotionEvent): Boolean {
        if (event.pointerCount <= 0) return true
        val signature = event.eventTime * 31 +
            event.actionMasked * 17L +
            event.pointerCount * 7L +
            (event.getX(0) * 10).toLong() * 13L +
            (event.getY(0) * 10).toLong()
        val now = SystemClock.uptimeMillis()
        if (signature == lastSignature && now - lastSignatureAt <= 4) return true
        lastSignature = signature
        lastSignatureAt = now
        return false
    }

    private fun refreshDisplaySize() {
        runCatching {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val display = manager.defaultDisplay
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            displayWidth = point.x
            displayHeight = point.y
            if (displayWidth <= 0 || displayHeight <= 0) {
                val metrics = resources.displayMetrics
                displayWidth = metrics.widthPixels
                displayHeight = metrics.heightPixels
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterController() {
        val target = controller ?: return
        val callback = controllerCallback ?: return
        runCatching { target.unregisterCallback(callback) }
    }

    private fun teardown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterController()
        controller = null
        controllerCallback = null
        GlobalCaptureBridge.markAvailable(false)
        GlobalCaptureBridge.detach(this)
        RecordingCoordinator.setAccessibilityAvailable(false)
    }
}

internal fun MotionEvent.toSamples(width: Double, height: Double): List<TouchSample> {
    if (width <= 0 || height <= 0) return emptyList()
    if (actionMasked == MotionEvent.ACTION_CANCEL) return emptyList()
    // 与 TouchPadView 语义一致：抬手的那根手指必须缺席本帧，采集层才能结算手势。
    val skipIndex = if (
        actionMasked == MotionEvent.ACTION_POINTER_UP || actionMasked == MotionEvent.ACTION_UP
    ) {
        actionIndex
    } else {
        -1
    }
    val result = ArrayList<TouchSample>(pointerCount)
    for (index in 0 until pointerCount) {
        if (index == skipIndex) continue
        val rawX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawX(index) else getX(index)
        val rawY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawY(index) else getY(index)
        val area = if (getSize(index) > 0f) getSize(index) else getPressure(index)
        result.add(
            TouchSample(
                pointerId = getPointerId(index),
                x = (rawX / width).coerceIn(0.0, 1.0),
                y = (rawY / height).coerceIn(0.0, 1.0),
                area = area.toDouble().coerceIn(0.0, 1.0),
                timeSec = eventTime / 1000.0,
            ),
        )
    }
    return result
}
