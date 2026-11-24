package com.touchbridge.rpe.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.touchbridge.rpe.MainActivity
import com.touchbridge.rpe.R
import com.touchbridge.rpe.capture.global.GlobalTouchBackend
import com.touchbridge.rpe.capture.global.NormalizedTouchFrame
import com.touchbridge.rpe.capture.global.RootInputBackend
import com.touchbridge.rpe.capture.global.ShizukuInputBackend
import com.touchbridge.rpe.capture.global.ShizukuSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统级后台采集前台服务。
 *
 * 状态机：
 * ARM（显示右上角控制条，准备采集通道）
 * → START（创建会话，RECORDING 只保留结束按钮）
 * → STOP（保存、写入 PendingCaptureResult、打开预览）
 * / BACK（未开始时取消服务并打开录制页）。
 *
 * Android 13+ 使用无障碍原始触摸；8–12 使用 Shizuku/Root 读取 /dev/input。
 */
class BackgroundCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlay: OverlayControlBar? = null
    private var windowManager: WindowManager? = null
    private var backend: GlobalTouchBackend? = null
    private var backendReady = false
    private var backendError: String? = null
    private var foregroundStarted = false
    private var lastNotificationMs = 0L
    private var serviceStopping = false
    private var backendResolving = false
    private var sessionStarting = false

    private val visibilityListener: (Boolean) -> Unit = { foreground ->
        syncOverlay(foreground)
        if (foreground && !RecordingCoordinator.state.value.recording &&
            RecordingCoordinator.state.value.phase == RecorderPhase.ARMED
        ) {
            // 尚未开始录制就回到 App：取消后台准备态，避免遗留无人操作的悬浮条/通知。
            shutdownInternal()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render(RecordingCoordinator.state.value)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> RecordingCoordinator.appendMarker("SCREEN_OFF")
                Intent.ACTION_SCREEN_ON -> RecordingCoordinator.appendMarker("SCREEN_ON")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        RecordingCoordinator.init(this)
        RecordingCoordinator.setServiceArmed(true)
        RecordingCoordinator.setAccessibilityAvailable(accessibilityCaptureActive())
        RecordingCoordinator.setPrivilegedAvailable(false)
        AppVisibilityTracker.addListener(visibilityListener)
        ensureForeground(RecordingCoordinator.state.value)
        mainHandler.post(ticker)
        scope.launch {
            RecordingCoordinator.state.collect { state -> render(state) }
        }
        // 服务创建后立即探测后端；此时应用通常还在前台，随后 moveTaskToBack。
        armBackend()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> stopSession()
            ACTION_BACK -> backToApp()
            ACTION_STOP_SERVICE -> if (RecordingCoordinator.state.value.recording) {
                stopSession()
            } else {
                backToApp()
            }
            else -> {
                ensureForeground(RecordingCoordinator.state.value)
                armBackend()
                syncOverlay(AppVisibilityTracker.isForeground)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onDisplayChanged()
        // 旋转后写 journal 几何标记，预览/恢复时才能解释归一化坐标。
        RecordingCoordinator.updateDisplayInfo(displayInfo())
    }

    override fun onDestroy() {
        AppVisibilityTracker.removeListener(visibilityListener)
        runCatching { unregisterReceiver(screenReceiver) }
        if (!serviceStopping && RecordingCoordinator.state.value.recording) {
            // 服务被系统回收时尽量结算正在录制的会话，避免 journal 悬空。
            backend?.stop()
            backend = null
            RecordingCoordinator.stop(StopReason.SERVICE_LOST) { result ->
                PendingCaptureResult.publish(result, StopReason.SERVICE_LOST)
                CaptureHandoffStore.markPending(this, result.sessionId)
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
        backend?.stop()
        backend = null
        hideOverlay()
        GlobalCaptureBridge.request(false)
        RecordingCoordinator.setPrivilegedAvailable(false)
        RecordingCoordinator.setServiceArmed(false)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 采集通道

    private fun armBackend() {
        if (backendResolving || backendReady || backendError != null || serviceStopping) return
        backendResolving = true
        showOverlay()
        render(RecordingCoordinator.state.value)
        scope.launch {
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) { resolveBackend() }
                }.getOrElse {
                    BackendResolution(ready = false, error = it.message ?: "采集通道探测失败")
                }
                if (serviceStopping) return@launch
                backendReady = result.ready
                backendError = result.error
                render(RecordingCoordinator.state.value)
                if (!result.ready) {
                    failToRecordPage(result.error ?: "采集通道不可用")
                }
            } finally {
                backendResolving = false
            }
        }
    }

    private suspend fun resolveBackend(): BackendResolution {
        if (accessibilityCaptureEnabled()) {
            // 用户从设置页返回后系统可能还没完成绑定；短暂等待，避免误报不可用。
            withTimeoutOrNull(1_500) {
                while (!GlobalCaptureBridge.attached || !GlobalCaptureBridge.isAvailable) delay(50)
            }
            if (GlobalCaptureBridge.attached && GlobalCaptureBridge.isAvailable) {
                RecordingCoordinator.setAccessibilityAvailable(true)
                RecordingCoordinator.setPrivilegedAvailable(false)
                return BackendResolution(ready = true, error = null)
            }
            RecordingCoordinator.setAccessibilityAvailable(false)
            return BackendResolution(
                ready = false,
                error = "无障碍服务已开启但触摸通道未就绪，请在系统设置中关闭后重新开启本应用的无障碍服务",
            )
        }
        RecordingCoordinator.setAccessibilityAvailable(false)

        if (ShizukuSetup.hasPermission()) {
            val shizuku = ShizukuInputBackend(this, ::onBackendError)
            if (shizuku.isAvailable()) {
                backend = shizuku
                RecordingCoordinator.setPrivilegedAvailable(true)
                return BackendResolution(ready = true, error = null)
            }
            shizuku.stop()
        }

        if (RootInputBackend.cachedAvailable == true) {
            val root = RootInputBackend(this, ::onBackendError)
            if (root.isAvailable()) {
                backend = root
                RecordingCoordinator.setPrivilegedAvailable(true)
                return BackendResolution(ready = true, error = null)
            }
            root.stop()
        }

        RecordingCoordinator.setPrivilegedAvailable(false)
        val message = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                "请开启本应用的无障碍服务"
            ShizukuSetup.isBinderAlive() ->
                "Shizuku 已连接，但当前设备不允许读取触摸设备；请改用 root 或应用内录制"
            RootInputBackend.hasSuBinary() ->
                "Root 已存在，但未授权或无法读取触摸设备"
            else ->
                "Android 8–12 需要 Shizuku 或 Root 才能全局采集"
        }
        return BackendResolution(ready = false, error = message)
    }

    private fun accessibilityCaptureEnabled(): Boolean =
        CaptureSupport.isAccessibilityCaptureSupported() && CaptureSupport.isAccessibilityEnabled(this)

    private fun accessibilityCaptureActive(): Boolean =
        CaptureSupport.isAccessibilityCaptureActive(this)

    private fun onBackendError(error: Throwable) {
        mainHandler.post {
            if (serviceStopping) return@post
            backend?.stop()
            backend = null
            backendReady = false
            backendError = error.message ?: "采集通道已断开"
            RecordingCoordinator.setPrivilegedAvailable(false)
            if (RecordingCoordinator.state.value.recording) {
                // 协调器会进入 DEGRADED，悬浮条仍允许“结束录制”以保存已有数据。
                Toast.makeText(this, "采集通道已断开，请结束录制以保存", Toast.LENGTH_SHORT).show()
                render(RecordingCoordinator.state.value)
            } else {
                // 尚未开始录制时通道断开，直接回录制页，避免悬浮条显示无法使用的“开始”。
                failToRecordPage(backendError ?: "采集通道已断开")
            }
        }
    }

    private data class BackendResolution(val ready: Boolean, val error: String?)

    // ------------------------------------------------------------ 会话控制

    private fun startSession() {
        val state = RecordingCoordinator.state.value
        if (sessionStarting || state.recording || !backendReady) return
        if (!CaptureSupport.canDrawOverlays(this)) {
            failToRecordPage("请先允许悬浮窗权限")
            return
        }
        sessionStarting = true
        val activeBackend = backend
        RecordingCoordinator.setAccessibilityAvailable(accessibilityCaptureActive())
        RecordingCoordinator.setPrivilegedAvailable(activeBackend != null)
        // 无障碍来源可能在应用回到前台时切换 owner，请求必须按会话而不是 owner 下发。
        if (accessibilityCaptureActive()) GlobalCaptureBridge.request(true)
        RecordingCoordinator.startBackground(displayInfo()) { started ->
            mainHandler.post {
                sessionStarting = false
                if (started) {
                    if (RecordingCoordinator.state.value.phase == RecorderPhase.DEGRADED) {
                        Toast.makeText(
                            this,
                            "采集通道未就绪，本次仅保留录制会话",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    activeBackend?.stop()
                    GlobalCaptureBridge.request(false)
                    backendError = RecordingCoordinator.state.value.message ?: "无法开始录制"
                }
                render(RecordingCoordinator.state.value)
            }
        }
        if (activeBackend != null) {
            activeBackend.start(::onBackendFrame, ::onBackendError)
        }
    }

    private fun stopSession() {
        if (!RecordingCoordinator.state.value.recording) return
        backend?.stop()
        backend = null
        RecordingCoordinator.stop(StopReason.USER) { result ->
            mainHandler.post { finishRecording(result, StopReason.USER) }
        }
    }

    private fun finishRecording(result: RecorderResult, reason: StopReason) {
        if (serviceStopping) return
        GlobalCaptureBridge.request(false)
        PendingCaptureResult.publish(result, reason)
        CaptureHandoffStore.markPending(this, result.sessionId)
        notifyFinished(result)
        // 先拉起预览页，再移除悬浮条并结束后台服务；Activity 启动失败时通知仍可兜底。
        openApp(MainActivity.DESTINATION_PREVIEW)
        shutdownInternal()
    }

    private fun backToApp() {
        if (RecordingCoordinator.state.value.recording) {
            stopSession()
            return
        }
        openApp(MainActivity.DESTINATION_RECORD)
        shutdownInternal()
    }

    private fun failToRecordPage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        openApp(MainActivity.DESTINATION_RECORD, message)
        shutdownInternal()
    }

    private fun shutdownInternal() {
        if (serviceStopping) return
        serviceStopping = true
        backend?.stop()
        backend = null
        GlobalCaptureBridge.request(false)
        hideOverlay()
        RecordingCoordinator.setPrivilegedAvailable(false)
        RecordingCoordinator.setServiceArmed(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    // ------------------------------------------------------------ 通知

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台采集", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台录制期间的常驻通知"
                setShowBadge(false)
            },
        )
    }

    private fun ensureForeground(state: RecorderState) {
        val notification = buildNotification(state)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val result = runCatching {
            if (foregroundStarted) {
                safeNotify(NOTIFICATION_ID, notification)
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                foregroundStarted = true
            }
        }
        if (result.isFailure) {
            Toast.makeText(this, "无法启动后台采集服务，请保持应用在前台后重试", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun buildNotification(state: RecorderState): Notification {
        val title = when {
            backendError != null -> "后台采集不可用"
            state.health == CaptureHealth.DEGRADED -> "后台采集已中断"
            state.phase == RecorderPhase.RECORDING -> "正在后台录制"
            backendReady -> "后台采集已就绪"
            else -> "正在准备后台采集"
        }
        val text = when {
            backendError != null -> backendError ?: "采集通道不可用"
            state.phase == RecorderPhase.RECORDING -> "${formatDuration(state)} · ${state.noteCount} 个手势"
            state.phase == RecorderPhase.DEGRADED -> state.message ?: "采集通道已断开"
            backendReady -> "点击悬浮条开始录制"
            else -> "正在校验 Shizuku / Root 通道"
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // 录制中/准备中点击通知都回到录制页，页面内有明确的停止/退出入口。
                putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_RECORD)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(state.recording)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        if (state.recording) {
            builder.addAction(
                0,
                "结束录制",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, BackgroundCaptureService::class.java).setAction(ACTION_STOP_SESSION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            builder.addAction(
                0,
                "退出后台模式",
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, BackgroundCaptureService::class.java).setAction(ACTION_BACK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun notifyFinished(result: RecorderResult) {
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_PREVIEW)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("录制完成")
            .setContentText("共 ${result.notes.size} 个手势，点击查看")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(FINISHED_ID, notification)
    }

    // ------------------------------------------------------------ 悬浮控制条

    private fun syncOverlay(foreground: Boolean) {
        if (foreground) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun showOverlay() {
        if (overlay != null || serviceStopping) return
        if (!CaptureSupport.canDrawOverlays(this)) return
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val bar = OverlayControlBar(this, prefs()).also { view ->
            view.listener = object : OverlayControlBar.Listener {
                override fun onStartRequested() = startSession()
                override fun onStopRequested() = stopSession()
                override fun onBackRequested() = backToApp()
            }
        }
        bar.attach(manager)
        windowManager = manager
        overlay = bar
        render(RecordingCoordinator.state.value)
    }

    private fun hideOverlay() {
        overlay?.detach()
        overlay = null
        windowManager = null
        OverlayTouchGuard.updateBounds(null)
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 渲染

    private fun render(state: RecorderState) {
        val bar = overlay ?: run {
            updateNotification(state)
            return
        }
        bar.mode = when {
            // 录制中后端断开时仍必须显示“结束录制”，否则用户只能从通知退出。
            state.phase == RecorderPhase.RECORDING -> OverlayControlBar.Mode.RECORDING
            state.phase == RecorderPhase.DEGRADED -> OverlayControlBar.Mode.DEGRADED
            backendError != null -> OverlayControlBar.Mode.ERROR
            !backendReady -> OverlayControlBar.Mode.BUSY
            else -> OverlayControlBar.Mode.ARMED
        }
        bar.elapsedMs = if (state.startedElapsedMs > 0) {
            (SystemClock.elapsedRealtime() - state.startedElapsedMs).coerceAtLeast(0L)
        } else {
            (state.durationSec * 1000.0).toLong()
        }
        updateNotification(state)
    }

    private fun updateNotification(state: RecorderState) {
        val now = SystemClock.uptimeMillis()
        if (foregroundStarted && now - lastNotificationMs >= 900L) {
            lastNotificationMs = now
            safeNotify(NOTIFICATION_ID, buildNotification(state))
        }
        if (!foregroundStarted) ensureForeground(state)
    }

    private fun formatDuration(state: RecorderState): String {
        val seconds = if (state.startedElapsedMs > 0 && state.recording) {
            ((SystemClock.elapsedRealtime() - state.startedElapsedMs) / 1000L).coerceAtLeast(0L)
        } else {
            state.durationSec.toLong().coerceAtLeast(0L)
        }
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun displayInfo(): DisplayInfo {
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val metrics = resources.displayMetrics
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        var rotation = 0
        if (manager != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = manager.maximumWindowMetrics.bounds
                    width = bounds.width()
                    height = bounds.height()
                } else {
                    @Suppress("DEPRECATION")
                    val point = android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }
                    width = point.x
                    height = point.y
                }
                @Suppress("DEPRECATION")
                rotation = manager.defaultDisplay.rotation
            }
        }
        return DisplayInfo(width, height, metrics.densityDpi, rotation)
    }

    private fun openApp(destination: String, error: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_DESTINATION, destination)
            if (!error.isNullOrBlank()) putExtra(MainActivity.EXTRA_CAPTURE_ERROR, error)
        }
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            safeNotify(
                FINISHED_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_capture)
                    .setContentTitle("后台采集")
                    .setContentText(error ?: "点击返回应用查看录制结果")
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            4,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    private fun onBackendFrame(frame: NormalizedTouchFrame) {
        // 直接在当前线程过滤并投递；协调器内部会再校验来源与 owner。
        RecordingCoordinator.submitShellFrame(frame)
    }

    companion object {
        const val ACTION_START = "com.touchbridge.rpe.capture.START"
        const val ACTION_STOP_SERVICE = "com.touchbridge.rpe.capture.STOP_SERVICE"
        const val ACTION_STOP_SESSION = "com.touchbridge.rpe.capture.STOP_SESSION"
        const val ACTION_BACK = "com.touchbridge.rpe.capture.BACK"

        private const val CHANNEL_ID = CaptureSupport.NOTIFICATION_CHANNEL_ID
        private const val NOTIFICATION_ID = 4301
        private const val FINISHED_ID = 4302
        private const val PREFS_NAME = "background_capture"

        /** @return true 表示系统已接受启动请求，调用方可以再退到桌面。 */
        fun start(context: Context): Boolean {
            val intent = Intent(context, BackgroundCaptureService::class.java).setAction(ACTION_START)
            val result = runCatching { ContextCompat.startForegroundService(context, intent) }
            result.exceptionOrNull()?.let {
                Toast.makeText(
                    context,
                    "无法启动后台采集：${it.message ?: "系统限制"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return result.isSuccess
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BackgroundCaptureService::class.java)
                .setAction(ACTION_STOP_SERVICE)
            runCatching { context.startService(intent) }
        }

        /** 从 App 内结束后台录制：必须回到服务状态机，才能同步通知/悬浮条并跳转预览。 */
        fun stopSession(context: Context) {
            val intent = Intent(context, BackgroundCaptureService::class.java)
                .setAction(ACTION_STOP_SESSION)
            runCatching { context.startService(intent) }
        }
    }
}
