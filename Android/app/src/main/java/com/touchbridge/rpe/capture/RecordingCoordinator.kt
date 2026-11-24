package com.touchbridge.rpe.capture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.touchbridge.rpe.capture.global.NormalizedTouchFrame
import com.touchbridge.rpe.domain.engine.RecordingSession
import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TouchSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class RecordingMode { FOREGROUND, BACKGROUND }

enum class RecorderPhase { IDLE, ARMED, RECORDING, DEGRADED }

enum class StopReason { USER, SERVICE_LOST, LOW_STORAGE, IO_ERROR, APP_EXIT, RECOVERED }

enum class CaptureHealth { OK, DEGRADED, ERROR }

enum class SessionTimebase { AUDIO, MONOTONIC }

data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
)

data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val mode: RecordingMode = RecordingMode.FOREGROUND,
    val owner: CaptureSource = CaptureSource.NONE,
    val noteCount: Int = 0,
    val durationSec: Double = 0.0,
    val startedElapsedMs: Long = 0L,
    val fingerCount: Int = 0,
    val health: CaptureHealth = CaptureHealth.OK,
    val message: String? = null,
    val sessionId: String? = null,
    val serviceArmed: Boolean = false,
) {
    val recording: Boolean get() = phase == RecorderPhase.RECORDING || phase == RecorderPhase.DEGRADED
}

data class RecorderResult(
    val notes: List<GestureNote>,
    val durationSec: Double,
    val sessionId: String?,
    val journalFile: File?,
)

/**
 * 进程级录制协调器：前台 View 采集与后台无障碍采集的唯一写者。
 */
object RecordingCoordinator {

    const val TAG = "RecordingCoordinator"
    private const val STATE_PUBLISH_INTERVAL_MS = 100L
    private const val MIN_FREE_BYTES = 32L * 1024 * 1024
    private const val MAX_KEPT_SESSIONS = 20

    const val MARKER_SESSION_START = "SESSION_START"
    const val MARKER_OWNER_VIEW = "OWNER_VIEW"
    const val MARKER_OWNER_ACCESSIBILITY = "OWNER_ACCESSIBILITY"
    const val MARKER_OWNER_SHELL = "OWNER_SHELL"
    const val MARKER_KEEPALIVE = "KEEPALIVE"
    const val MARKER_SERVICE_LOST = "SERVICE_LOST"
    const val MARKER_GAP_END = "GAP_END"

    private val lock = ReentrantLock()
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var rootDir: File
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val router = CaptureRouter()
    @Volatile
    private var session: RecordingSession? = null
    private var journal: RecordingJournal? = null
    private var clock: SessionClock? = null

    @Volatile
    private var settingsValue = ChartSettings()

    private var phase = RecorderPhase.IDLE
    private var mode = RecordingMode.FOREGROUND
    private var timebase = SessionTimebase.MONOTONIC
    private var displayInfo = DisplayInfo(0, 0, 0, 0)
    private var accessibilityAvailable = false
    private var privilegedAvailable = false
    private var appForeground = true
    private var serviceArmed = false
    private var lastFrameTimeSec = 0.0
    private var lastPublishMs = 0L
    private var fingerCount = 0
    private var sessionId: String? = null
    private var message: String? = null
    private var health = CaptureHealth.OK
    private var rejectedFrames = 0L

    @Volatile
    private var lastNoteCache: GestureNote? = null

    fun init(context: Context, directory: File = File(context.filesDir, "recording")) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        rootDir = directory
        if (!rootDir.exists()) rootDir.mkdirs()
        session = RecordingSession { settingsValue }
        val thread = HandlerThread("rec-capture").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
        RecordingRecovery.prune(rootDir, keep = 20)
    }

    fun updateSettings(settings: ChartSettings) {
        settingsValue = settings
    }

    // ------------------------------------------------------------ 会话生命周期

    fun startForeground(
        display: DisplayInfo,
        audioBacked: Boolean,
        onStarted: ((Boolean) -> Unit)? = null,
    ) = post {
        if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.DEGRADED) {
            onStarted?.invoke(false)
            return@post
        }
        if (!hasFreeSpace()) {
            message = "存储空间不足，无法开始录制"
            health = CaptureHealth.ERROR
            publish(force = true)
            onStarted?.invoke(false)
            return@post
        }
        mode = RecordingMode.FOREGROUND
        timebase = if (audioBacked) SessionTimebase.AUDIO else SessionTimebase.MONOTONIC
        beginSession(display, SessionHeader.SOURCE_VIEW_ONLY, timebase.journalName())
        switchOwner(CaptureSource.VIEW)
        publish(force = true)
        onStarted?.invoke(true)
    }

    /**
     * 由悬浮按钮触发。调用时应用已退至后台，因此直接取得全局采集所有权。
     * 无障碍不可用时进入 DEGRADED（仅会话存活，不接收任何帧）。
     */
    fun startBackground(
        display: DisplayInfo,
        onStarted: ((Boolean) -> Unit)? = null,
    ) = post {
        if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.DEGRADED) {
            onStarted?.invoke(false)
            return@post
        }
        if (!hasFreeSpace()) {
            message = "存储空间不足，无法开始录制"
            health = CaptureHealth.ERROR
            publish(force = true)
            onStarted?.invoke(false)
            return@post
        }
        mode = RecordingMode.BACKGROUND
        timebase = SessionTimebase.MONOTONIC
        beginSession(display, SessionHeader.SOURCE_BACKGROUND, SessionHeader.TIMEBASE_MONOTONIC)
        val source = preferredBackgroundSource()
        if (source != CaptureSource.NONE) {
            switchOwner(source)
            phase = RecorderPhase.RECORDING
            health = CaptureHealth.OK
        } else {
            phase = RecorderPhase.DEGRADED
            health = CaptureHealth.DEGRADED
            message = "未检测到可用的全局采集通道，本次仅保留录制会话"
            journal?.appendMarker(MARKER_SERVICE_LOST, lastFrameTimeSec)
        }
        publish(force = true)
        onStarted?.invoke(true)
    }

    fun stop(reason: StopReason = StopReason.USER, onStopped: ((RecorderResult) -> Unit)? = null) = post {
        onStopped?.invoke(stopLocked(reason))
    }

    /** 中断后由用户显式确认恢复采集。 */
    fun resumeAfterDegraded(onResumed: ((Boolean) -> Unit)? = null) = post {
        val source = preferredBackgroundSource()
        if (phase != RecorderPhase.DEGRADED || source == CaptureSource.NONE) {
            onResumed?.invoke(false)
            return@post
        }
        journal?.appendMarker(MARKER_GAP_END, lastFrameTimeSec)
        switchOwner(source)
        phase = RecorderPhase.RECORDING
        health = CaptureHealth.OK
        message = null
        publish(force = true)
        onResumed?.invoke(true)
    }

    private fun beginSession(display: DisplayInfo, source: String, journalTimebase: String) {
        lock.withLock {
            displayInfo = display
            clock = SessionClock()
            val current = session ?: RecordingSession { settingsValue }.also { session = it }
            current.start(0.0)
            lastFrameTimeSec = 0.0
            fingerCount = 0
            message = null
            health = CaptureHealth.OK
            rejectedFrames = 0
            router.reset()
            openJournal(source, journalTimebase)
            phase = RecorderPhase.RECORDING
        }
    }

    private fun stopLocked(reason: StopReason): RecorderResult = lock.withLock {
        val current = session
        if (current == null || !current.isRecording) {
            return@withLock RecorderResult(
                notes = current?.notes?.toList() ?: emptyList(),
                durationSec = 0.0,
                sessionId = sessionId,
                journalFile = journal?.file,
            )
        }
        val stopAt = when (timebase) {
            SessionTimebase.AUDIO -> maxOf(lastFrameTimeSec, current.lastFrameTimeSec)
            SessionTimebase.MONOTONIC -> maxOf(clock?.now() ?: 0.0, current.lastFrameTimeSec)
        }
        val before = current.notes.size
        current.stop(stopAt)
        persistSince(before)
        val file = journal?.file
        journal?.appendMarker("STOP_${reason.name}", stopAt)
        journal?.finish(reason.name.lowercase(), stopAt)
        journal?.close()
        journal = null
        switchOwner(CaptureSource.NONE)
        clock = null
        phase = if (mode == RecordingMode.BACKGROUND && serviceArmed) {
            RecorderPhase.ARMED
        } else {
            RecorderPhase.IDLE
        }
        health = CaptureHealth.OK
        fingerCount = 0
        if (reason == StopReason.LOW_STORAGE) message = "存储空间不足，录制已停止"
        if (reason == StopReason.IO_ERROR) message = "写入失败，录制已停止"
        publish(force = true)
        RecorderResult(
            notes = current.notes.toList(),
            durationSec = maxOf(0.0, stopAt - current.startTimeSec),
            sessionId = sessionId,
            journalFile = file,
        )
    }

    // ------------------------------------------------------------ 帧投递

    fun submitViewFrame(samples: List<TouchSample>, uptimeNanos: Long, audioTimeSec: Double?) = post {
        if (phase != RecorderPhase.RECORDING) return@post
        val token = router.currentToken(CaptureSource.VIEW) ?: return@post
        val time = when (timebase) {
            SessionTimebase.AUDIO -> audioTimeSec ?: return@post
            SessionTimebase.MONOTONIC -> clock?.timeAt(uptimeNanos / 1_000_000) ?: return@post
        }
        applyFrame(CaptureSource.VIEW, token, samples, time)
    }

    fun submitGlobalFrame(
        samples: List<TouchSample>,
        eventUptimeMs: Long,
        source: CaptureSource = CaptureSource.ACCESSIBILITY,
    ) = post {
        submitGlobalLocked(samples, eventUptimeMs, source)
    }

    /**
     * Shizuku/Root 后端每帧包含全部活动手指；落点位于悬浮条内的手指会被剔除，
     * 避免把“结束录制”等控制操作写进谱面。
     */
    fun submitShellFrame(frame: NormalizedTouchFrame) = post {
        val gamePointers = frame.pointers.filterNot { point ->
            OverlayTouchGuard.isOverlayPointNormalized(point.x, point.y)
        }
        // 整帧都在悬浮条上：丢弃；但真正的空帧（全部抬起）仍要提交以结算手指。
        if (frame.pointers.isNotEmpty() && gamePointers.isEmpty()) return@post
        val samples = gamePointers.map { point ->
            TouchSample(
                pointerId = point.pointerId,
                x = point.x.toDouble().coerceIn(0.0, 1.0),
                y = point.y.toDouble().coerceIn(0.0, 1.0),
                area = 0.5,
                timeSec = frame.timeMs / 1000.0,
            )
        }
        submitGlobalLocked(samples, frame.timeMs, CaptureSource.SHELL)
    }

    private fun submitGlobalLocked(
        samples: List<TouchSample>,
        eventUptimeMs: Long,
        source: CaptureSource,
    ) {
        if (phase != RecorderPhase.RECORDING) return
        if (source != CaptureSource.ACCESSIBILITY && source != CaptureSource.SHELL) return
        val token = router.currentToken(source) ?: return
        val time = clock?.timeAt(eventUptimeMs) ?: return
        applyFrame(source, token, samples, time)
    }

    private fun applyFrame(
        source: CaptureSource,
        token: Long,
        samples: List<TouchSample>,
        timeSec: Double,
    ) {
        lock.withLock {
            if (!router.accepts(source, token)) {
                rejectedFrames += 1
                return
            }
            val current = session ?: return
            val safeTime = maxOf(timeSec, current.lastFrameTimeSec)
            val before = current.notes.size
            current.onTouchFrame(samples, safeTime)
            lastFrameTimeSec = safeTime
            fingerCount = samples.size
            lastNoteCache = current.notes.lastOrNull()
            persistSince(before)
            publish(force = false)
        }
    }

    private fun persistSince(fromIndex: Int) {
        val current = session ?: return
        val target = journal ?: return
        if (current.notes.size <= fromIndex) return
        for (index in fromIndex until current.notes.size) {
            if (!target.appendNote(current.notes[index])) {
                health = CaptureHealth.ERROR
                message = "日志写入失败，录制已停止"
                break
            }
        }
        if (target.failure != null) {
            this.journal = null
            target.close()
            if (phase == RecorderPhase.RECORDING) stopLocked(StopReason.IO_ERROR)
        }
    }

    // ------------------------------------------------------------ 来源切换

    fun acquire(source: CaptureSource) = post { switchOwner(source) }

    fun release(source: CaptureSource) = post {
        if (router.isOwner(source)) switchOwner(CaptureSource.NONE)
    }

    private fun switchOwner(source: CaptureSource) {
        lock.withLock {
            val result = if (source == CaptureSource.NONE) {
                router.release(router.owner)
            } else {
                router.acquire(source)
            }
            if (result.flushNeeded) settleOpenPointers()
            publish(force = true)
        }
    }

    /** 切换来源前结算仍在按下的手指，避免跨来源粘连成同一个拖拽。 */
    private fun settleOpenPointers() {
        val current = session ?: return
        if (!current.isRecording) return
        val before = current.notes.size
        // +1ms 保证结算帧时间严格前进，满足引擎的单调时间约束。
        current.onTouchFrame(emptyList(), maxOf(lastFrameTimeSec, current.lastFrameTimeSec) + 0.001)
        persistSince(before)
    }

    // ------------------------------------------------------------ 前后台

    fun onAppForeground() = post {
        appForeground = true
        if (phase == RecorderPhase.DEGRADED && mode == RecordingMode.BACKGROUND) {
            // 回到 App 后 VIEW 来源一定可用，恢复采集，避免用户被困在中断态。
            journal?.appendMarker(MARKER_GAP_END, lastFrameTimeSec)
            switchOwner(CaptureSource.VIEW)
            phase = RecorderPhase.RECORDING
            health = CaptureHealth.OK
            message = null
            publish(force = true)
            return@post
        }
        if (phase != RecorderPhase.RECORDING) return@post
        if (router.isOwner(CaptureSource.ACCESSIBILITY) || router.isOwner(CaptureSource.SHELL)) {
            journal?.appendMarker(MARKER_OWNER_VIEW, lastFrameTimeSec)
        }
        switchOwner(CaptureSource.VIEW)
    }

    fun onAppBackground() = post {
        appForeground = false
        if (phase != RecorderPhase.RECORDING) {
            publish(force = true)
            return@post
        }
        when {
            mode == RecordingMode.BACKGROUND && preferredBackgroundSource() != CaptureSource.NONE -> {
                val source = preferredBackgroundSource()
                journal?.appendMarker(
                    if (source == CaptureSource.SHELL) MARKER_OWNER_SHELL else MARKER_OWNER_ACCESSIBILITY,
                    lastFrameTimeSec,
                )
                switchOwner(source)
            }
            mode == RecordingMode.BACKGROUND -> {
                journal?.appendMarker(MARKER_SERVICE_LOST, lastFrameTimeSec)
                phase = RecorderPhase.DEGRADED
                health = CaptureHealth.DEGRADED
                message = "后台采集通道不可用，录制已暂停；可返回 App 继续"
                switchOwner(CaptureSource.NONE)
            }
            else -> {
                // 音频模式：不切全局采集，只保活会话，避免时间轴跳变。
                journal?.appendMarker(MARKER_KEEPALIVE, lastFrameTimeSec)
                switchOwner(CaptureSource.NONE)
            }
        }
        publish(force = true)
    }

    // ------------------------------------------------------------ 服务状态

    fun setServiceArmed(armed: Boolean) = post {
        serviceArmed = armed
        if (!armed && phase == RecorderPhase.ARMED) phase = RecorderPhase.IDLE
        publish(force = true)
    }

    fun setAccessibilityAvailable(available: Boolean) = post {
        val previous = accessibilityAvailable
        accessibilityAvailable = available
        if (!available && phase == RecorderPhase.RECORDING &&
            mode == RecordingMode.BACKGROUND &&
            router.isOwner(CaptureSource.ACCESSIBILITY)
        ) {
            journal?.appendMarker(MARKER_SERVICE_LOST, lastFrameTimeSec)
            phase = RecorderPhase.DEGRADED
            health = CaptureHealth.DEGRADED
            message = "无障碍服务已断开，采集暂停"
            switchOwner(CaptureSource.NONE)
        }
        if (available && !previous && phase == RecorderPhase.DEGRADED) {
            message = "采集通道已恢复，点击继续采集"
        }
        publish(force = true)
    }

    /** 由 Shizuku/Root 后端在探针成功后置位；断开时自动降级。 */
    fun setPrivilegedAvailable(available: Boolean) = post {
        val previous = privilegedAvailable
        privilegedAvailable = available
        if (!available && phase == RecorderPhase.RECORDING &&
            mode == RecordingMode.BACKGROUND &&
            router.isOwner(CaptureSource.SHELL)
        ) {
            journal?.appendMarker(MARKER_SERVICE_LOST, lastFrameTimeSec)
            phase = RecorderPhase.DEGRADED
            health = CaptureHealth.DEGRADED
            message = "Shizuku/Root 采集通道已断开，录制暂停"
            switchOwner(CaptureSource.NONE)
        }
        if (available && !previous && phase == RecorderPhase.DEGRADED) {
            message = "采集通道已恢复，点击继续采集"
        }
        publish(force = true)
    }

    private fun preferredBackgroundSource(): CaptureSource = when {
        accessibilityAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            CaptureSource.ACCESSIBILITY
        privilegedAvailable -> CaptureSource.SHELL
        else -> CaptureSource.NONE
    }

    fun updateDisplayInfo(display: DisplayInfo): Unit = post {
        displayInfo = display
        if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.DEGRADED) {
            journal?.appendGeometry(display.widthPx, display.heightPx, display.rotation, lastFrameTimeSec)
        }
    }

    // ------------------------------------------------------------ 快照

    fun notesSnapshot(): List<GestureNote> = lock.withLock {
        session?.notes?.toList() ?: emptyList()
    }

    fun lastNote(): GestureNote? = lastNoteCache

    /** 记录屏幕开关、旋转等外部事件，便于事后解释时间轴空洞。 */
    fun appendMarker(name: String) = post {
        if (phase != RecorderPhase.RECORDING && phase != RecorderPhase.DEGRADED) return@post
        journal?.appendMarker(name, lastFrameTimeSec)
    }

    fun replaceNotes(notes: List<GestureNote>) = post {
        lock.withLock {
            session?.loadSimulated(notes)
            publish(force = true)
        }
    }

    fun clearNotes() = replaceNotes(emptyList())

    fun isRecording(): Boolean = phase == RecorderPhase.RECORDING || phase == RecorderPhase.DEGRADED

    fun rejectedFrameCount(): Long = rejectedFrames

    // ------------------------------------------------------------ 内部工具

    private fun openJournal(source: String, journalTimebase: String) {
        runCatching {
            val header = SessionHeader(
                sessionId = RecordingJournal.newSessionId(),
                createdAtMs = System.currentTimeMillis(),
                timebase = journalTimebase,
                source = source,
                appVersion = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
                }.getOrDefault(""),
                displayWidth = displayInfo.widthPx,
                displayHeight = displayInfo.heightPx,
                densityDpi = displayInfo.densityDpi,
                rotation = displayInfo.rotation,
                settings = settingsValue,
            )
            sessionId = header.sessionId
            journal = RecordingJournal.create(rootDir, header) { error -> onJournalError(error) }
            journal?.appendMarker(MARKER_SESSION_START, 0.0)
            RecordingRecovery.prune(rootDir, MAX_KEPT_SESSIONS)
        }.onFailure {
            journal = null
            health = CaptureHealth.ERROR
            message = "无法创建录制日志：${it.message ?: "未知错误"}"
        }
    }

    private fun onJournalError(error: IOException) {
        health = CaptureHealth.ERROR
        message = "日志写入失败：${error.message ?: "IO 错误"}"
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublishMs < STATE_PUBLISH_INTERVAL_MS) return
        lastPublishMs = now
        val current = session
        val startedElapsed = clock?.startedElapsedMs ?: 0L
        val duration = if (current != null && current.isRecording) {
            maxOf(0.0, lastFrameTimeSec - current.startTimeSec)
        } else {
            _state.value.durationSec
        }
        _state.value = RecorderState(
            phase = phase,
            mode = mode,
            owner = router.owner,
            noteCount = current?.notes?.size ?: 0,
            durationSec = duration,
            startedElapsedMs = startedElapsed,
            fingerCount = fingerCount,
            health = health,
            message = message,
            sessionId = sessionId,
            serviceArmed = serviceArmed,
        )
    }

    private fun hasFreeSpace(): Boolean = runCatching {
        rootDir.usableSpace > MIN_FREE_BYTES
    }.getOrDefault(true)

    private fun post(block: () -> Unit) {
        val target = handler
        if (target == null) {
            block()
            return
        }
        if (Looper.myLooper() == target.looper) block() else target.post(block)
    }

    private fun SessionTimebase.journalName(): String = when (this) {
        SessionTimebase.AUDIO -> SessionHeader.TIMEBASE_AUDIO
        SessionTimebase.MONOTONIC -> SessionHeader.TIMEBASE_MONOTONIC
    }
}
