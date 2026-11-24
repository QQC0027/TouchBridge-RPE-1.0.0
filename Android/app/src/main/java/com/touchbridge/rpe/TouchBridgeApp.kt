package com.touchbridge.rpe

import android.app.Application
import com.touchbridge.rpe.capture.AppVisibilityTracker
import com.touchbridge.rpe.capture.CaptureSupport
import com.touchbridge.rpe.capture.RecordingCoordinator
import com.touchbridge.rpe.capture.global.ShizukuSetup

class TouchBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RecordingCoordinator.init(this)
        ShizukuSetup.init()
        // 只有触摸事件通道真正注册成功才算可用；设置页开关不等于能收到事件。
        RecordingCoordinator.setAccessibilityAvailable(
            CaptureSupport.isAccessibilityCaptureActive(this),
        )
        AppVisibilityTracker.register(this)
    }
}
