package com.touchbridge.rpe.capture

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.touchbridge.rpe.capture.global.RootInputBackend
import com.touchbridge.rpe.capture.global.ShizukuSetup

/**
 * 后台采集的能力矩阵。
 *
 * 13+ 使用无障碍原始触摸；8–12 没有公开 API，必须由 Shizuku/Root 读取
 * /dev/input。未授权时如实返回不可用，不伪造 READY。
 */
object CaptureSupport {

    const val UI_MIN_SDK = Build.VERSION_CODES.O

    /** 无障碍原始触摸的起始版本；13 之前走 Shizuku/Root。 */
    const val MIN_SDK = Build.VERSION_CODES.TIRAMISU

    /** 特权读取 /dev/input 的最低版本。 */
    const val PRIVILEGED_MIN_SDK = Build.VERSION_CODES.O

    /** 后台采集前台服务使用的通知通道。 */
    const val NOTIFICATION_CHANNEL_ID = "background_capture"

    enum class Requirement(val title: String) {
        ACCESSIBILITY("无障碍服务"),
        PRIVILEGED("Shizuku / Root"),
        OVERLAY("悬浮窗"),
        NOTIFICATION("通知"),
    }

    /** 后台采集可用程度。 */
    enum class Level {
        /** Android 13+ 且无障碍服务已开启，可以全局采集。 */
        READY,

        /** Android 8–12 且 Shizuku/Root 已授权，进入后仍需校验设备可读性。 */
        PRIVILEGED_READY,

        /** 权限未齐。 */
        NEEDS_SETUP,

        /** Android 8–12 尚无可用的 Shizuku/Root。 */
        NEEDS_SHIZUKU_OR_ROOT,

        /** 系统版本低于 8.0。 */
        UNSUPPORTED,
    }

    fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= UI_MIN_SDK

    fun isAccessibilityCaptureSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** “设置中已启用”且触摸事件通道已经真正注册成功。 */
    fun isAccessibilityCaptureActive(context: Context): Boolean =
        isAccessibilityEnabled(context) &&
            GlobalCaptureBridge.attached &&
            GlobalCaptureBridge.isAvailable

    /** 13+ 无障碍或 8–12 Shizuku/Root 都算全局采集能力。 */
    fun isGlobalCaptureSupported(): Boolean =
        isPlatformSupported() && (isAccessibilityCaptureSupported() || Build.VERSION.SDK_INT >= PRIVILEGED_MIN_SDK)

    /** 仅表示“已授权”，设备是否真的可读 /dev/input 由服务探针确认。 */
    fun hasPrivilegedCapability(): Boolean =
        ShizukuSetup.hasPermission() || RootInputBackend.cachedAvailable == true

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val services = manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ) ?: return false
        val target = GlobalTouchAccessibilityService::class.java.name
        return services.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == context.packageName && serviceInfo.name == target
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // 通道被用户单独关闭时，前台服务仍能运行但常驻通知不可见。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    fun missingRequirements(context: Context): List<Requirement> = buildList {
        if (!canDrawOverlays(context)) add(Requirement.OVERLAY)
        if (!canPostNotifications(context)) add(Requirement.NOTIFICATION)
        when {
            isAccessibilityCaptureSupported() -> {
                if (!isAccessibilityEnabled(context) && !hasPrivilegedCapability()) {
                    add(Requirement.ACCESSIBILITY)
                }
            }
            isPlatformSupported() -> {
                if (!hasPrivilegedCapability()) add(Requirement.PRIVILEGED)
            }
        }
    }

    fun level(context: Context): Level = when {
        !isPlatformSupported() -> Level.UNSUPPORTED
        !canDrawOverlays(context) -> Level.NEEDS_SETUP
        isAccessibilityCaptureSupported() -> {
            if (isAccessibilityEnabled(context) || hasPrivilegedCapability()) Level.READY else Level.NEEDS_SETUP
        }
        hasPrivilegedCapability() -> Level.PRIVILEGED_READY
        else -> Level.NEEDS_SHIZUKU_OR_ROOT
    }

    fun canStartBackgroundCapture(context: Context): Boolean {
        if (!isPlatformSupported() || !canDrawOverlays(context)) return false
        return if (isAccessibilityCaptureSupported()) {
            isAccessibilityEnabled(context) || hasPrivilegedCapability()
        } else {
            hasPrivilegedCapability()
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ------------------------------------------------------------ 设置页跳转

    fun openAccessibilitySettings(context: Context) {
        launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        launch(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    fun openNotificationSettings(context: Context) {
        launch(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    /** 仅打开电池优化设置页，不申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限。 */
    fun openBatterySettings(context: Context) {
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun launch(context: Context, intent: Intent) {
        val safe = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(safe) }
    }
}
