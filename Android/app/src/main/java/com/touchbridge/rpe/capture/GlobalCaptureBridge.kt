package com.touchbridge.rpe.capture

import android.os.Handler
import android.os.Looper

/**
 * 无障碍服务实例与“是否请求全局采集”之间的进程内桥梁。
 *
 * 服务实例由系统绑定，其他组件无法直接持有引用，因此通过这里下发开关；
 * serviceInfo 的修改统一切回主线程执行。
 */
object GlobalCaptureBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isRequested: Boolean = false
        private set

    /** 已连接且当前 API 上的事件通道确实注册成功；仅 attached 不代表能出帧。 */
    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    private var service: GlobalTouchAccessibilityService? = null

    val attached: Boolean get() = service != null

    fun attach(target: GlobalTouchAccessibilityService) {
        service = target
        isAvailable = false
        mainHandler.post { target.applyCaptureRequest(isRequested) }
    }

    fun markAvailable(available: Boolean) {
        isAvailable = available
    }

    fun detach(target: GlobalTouchAccessibilityService) {
        if (service === target) {
            service = null
            isAvailable = false
        }
    }

    fun request(active: Boolean) {
        isRequested = active
        val target = service ?: return
        mainHandler.post { target.applyCaptureRequest(active) }
    }
}
