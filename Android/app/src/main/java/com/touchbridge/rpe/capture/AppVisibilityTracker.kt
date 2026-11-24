package com.touchbridge.rpe.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程级前后台判定。用 started 计数而不是单 Activity 的 onResume/onPause，
 * 避免多 Activity/权限设置页跳转时误判。
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedCount = 0

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
        if (startedCount == 1) {
            isForeground = true
            RecordingCoordinator.onAppForeground()
            listeners.forEach { it(true) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = maxOf(0, startedCount - 1)
        if (startedCount == 0) {
            isForeground = false
            RecordingCoordinator.onAppBackground()
            listeners.forEach { it(false) }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
