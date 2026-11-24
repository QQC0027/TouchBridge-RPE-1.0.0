package com.touchbridge.rpe.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.touchbridge.rpe.audio.AudioEngine
import com.touchbridge.rpe.capture.BackgroundCaptureService
import com.touchbridge.rpe.capture.CaptureHandoffStore
import com.touchbridge.rpe.capture.DisplayInfo
import com.touchbridge.rpe.capture.PendingCaptureResult
import com.touchbridge.rpe.capture.RecorderPhase
import com.touchbridge.rpe.capture.RecordingCoordinator
import com.touchbridge.rpe.capture.RecordingMode
import com.touchbridge.rpe.capture.RecordingRecovery
import com.touchbridge.rpe.capture.StopReason
import com.touchbridge.rpe.data.ProjectRepository
import com.touchbridge.rpe.data.SettingsRepository
import com.touchbridge.rpe.domain.autochart.AudioAnalyzer
import com.touchbridge.rpe.domain.autochart.SnapAligner
import com.touchbridge.rpe.domain.engine.BeatClock
import com.touchbridge.rpe.domain.engine.Calibrator
import com.touchbridge.rpe.domain.engine.RpeBuilder
import com.touchbridge.rpe.domain.engine.RpeBuildResult
import com.touchbridge.rpe.domain.engine.SimulatedSession
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.RpeNote
import com.touchbridge.rpe.domain.model.TouchSample
import com.touchbridge.rpe.export.AudioMetaReader
import com.touchbridge.rpe.export.InfoFiles
import com.touchbridge.rpe.export.MediaExporter
import com.touchbridge.rpe.export.PezPackager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val projectRepo = ProjectRepository(app)
    private val exporter = MediaExporter(app)
    private val audio = AudioEngine(app)

    var settings by mutableStateOf(settingsRepo.loadSettings())
        private set
    var meta by mutableStateOf(settingsRepo.loadMeta())
        private set
    var audioInfo by mutableStateOf(projectRepo.existingAudio())
        private set
    var coverInfo by mutableStateOf(projectRepo.existingCover())
        private set

    private val coordinator = RecordingCoordinator

    var recording by mutableStateOf(false)
        private set
    var noteCount by mutableStateOf(0)
        private set
    var fingerCount by mutableStateOf(0)
        private set
    var backgroundRecording by mutableStateOf(false)
        private set
    var backgroundMessage by mutableStateOf<String?>(null)
        private set
    var recoveryPrompt by mutableStateOf<RecoveryPrompt?>(null)
        private set
    var gestureHint by mutableStateOf("待机")
        private set
    var musicTime by mutableStateOf(0.0)
        private set
    var audioPlaying by mutableStateOf(false)
        private set
    var audioReady by mutableStateOf(false)
        private set

    var previewNotes by mutableStateOf<List<RpeNote>>(emptyList())
        private set
    var stats by mutableStateOf<com.touchbridge.rpe.domain.engine.ChartStats?>(null)
        private set

    var toast by mutableStateOf<ToastMsg?>(null)
        private set
    var exporting by mutableStateOf(false)
        private set
    var lastExport by mutableStateOf<ExportOutcome?>(null)
        private set

    data class ToastMsg(val text: String, val level: String, val id: Long = System.nanoTime())
    data class ExportOutcome(
        val path: String,
        val stats: com.touchbridge.rpe.domain.engine.ChartStats?,
        val fileName: String,
        val warning: String? = null,
    )

    data class RecoveryPrompt(
        val sessionId: String,
        val noteCount: Int,
        val durationSec: Double,
        val damaged: Boolean,
    )

    private var lastHudNanos = 0L
    private var lastGesture: String = "待机"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveSettingsRunnable = Runnable { settingsRepo.saveSettings(settings) }

    private var pendingRecovery: RecordingRecovery.RecoveredSession? = null

    init {
        coordinator.init(getApplication())
        coordinator.updateSettings(settings)
        var wasRecording = false
        viewModelScope.launch {
            coordinator.state.collect { state ->
                val endedElsewhere = wasRecording && !state.recording
                wasRecording = state.recording
                recording = state.recording
                noteCount = state.noteCount
                fingerCount = state.fingerCount
                backgroundRecording = state.mode == RecordingMode.BACKGROUND && state.recording
                backgroundMessage = state.message
                if (state.phase == RecorderPhase.DEGRADED) gestureHint = "采集中断"
                if (endedElsewhere) {
                    // 后台结束不经过 stopRecording 回调；必须无条件重建，否则会停留在旧预览。
                    gestureHint = "已结束"
                    rebuildPreview()
                }
            }
        }
        viewModelScope.launch {
            val handoff = PendingCaptureResult.take()
            if (handoff != null) {
                CaptureHandoffStore.takePending(getApplication())
                coordinator.replaceNotes(handoff.notes)
                noteCount = handoff.notes.size
                gestureHint = "已结束"
                rebuildPreview(handoff.notes)
                showToast("后台录制已保存，共 ${handoff.notes.size} 个手势", "success")
                return@launch
            }
            // 进程在服务结束与 Activity 启动之间被杀：从已完成 journal 重建。
            val sessionId = CaptureHandoffStore.takePending(getApplication()) ?: return@launch
            val root = File(getApplication<Application>().filesDir, "recording")
            val recovered = withContext(Dispatchers.IO) {
                RecordingRecovery.load(File(root, sessionId))
            }
            if (recovered != null && recovered.ended && recovered.notes.isNotEmpty()) {
                coordinator.replaceNotes(recovered.notes)
                noteCount = recovered.notes.size
                gestureHint = "已结束"
                rebuildPreview(recovered.notes)
                showToast("已恢复录制结果，共 ${recovered.notes.size} 个手势", "success")
            }
        }
        viewModelScope.launch {
            // 后台服务正在写入的会话不是“崩溃残留”，不能提示恢复并覆盖当前录制。
            if (coordinator.state.value.recording) return@launch
            val root = File(getApplication<Application>().filesDir, "recording")
            val pending = withContext(Dispatchers.IO) { RecordingRecovery.pending(root).firstOrNull() }
            if (pending != null) {
                pendingRecovery = pending
                recoveryPrompt = RecoveryPrompt(
                    sessionId = pending.sessionId,
                    noteCount = pending.noteCount,
                    durationSec = pending.durationSec,
                    damaged = pending.damagedTail,
                )
            }
        }
        audioInfo?.let { info ->
            runCatching {
                audio.load(info.file)
                audioReady = true
            }
        }
    }

    // ---------------------------------------------------------------- 恢复

    fun recoverPendingSession() {
        val pending = pendingRecovery ?: return
        pendingRecovery = null
        recoveryPrompt = null
        coordinator.replaceNotes(pending.notes)
        noteCount = pending.notes.size
        rebuildPreview(pending.notes)
        showToast("已恢复 ${pending.notes.size} 个手势", "success")
    }

    /** 服务已结束但进程内交接丢失时，尝试用最近一次已完成会话重建预览。 */
    fun recoverLatestCompletedForPreview() {
        if (previewNotes.isNotEmpty()) return
        viewModelScope.launch {
            val root = File(getApplication<Application>().filesDir, "recording")
            val recovered = withContext(Dispatchers.IO) { RecordingRecovery.latestCompleted(root) }
                ?: return@launch
            if (previewNotes.isNotEmpty()) return@launch
            coordinator.replaceNotes(recovered.notes)
            noteCount = recovered.notes.size
            gestureHint = "已结束"
            rebuildPreview(recovered.notes)
        }
    }

    fun discardPendingSession() {
        val pending = pendingRecovery ?: return
        pendingRecovery = null
        recoveryPrompt = null
        val root = File(getApplication<Application>().filesDir, "recording")
        viewModelScope.launch(Dispatchers.IO) {
            RecordingRecovery.delete(root, pending.sessionId)
        }
    }

    // ---------------------------------------------------------------- 设置

    fun updateSettings(transform: (ChartSettings) -> ChartSettings) {
        settings = transform(settings)
        coordinator.updateSettings(settings)
        mainHandler.removeCallbacks(saveSettingsRunnable)
        mainHandler.postDelayed(saveSettingsRunnable, 500L)
    }

    fun updateMeta(transform: (ChartMeta) -> ChartMeta) {
        meta = transform(meta)
        settingsRepo.saveMeta(meta)
    }

    fun clockFor(s: ChartSettings = settings): BeatClock =
        BeatClock(bpm = s.bpm, offsetMs = s.offsetMs, beatsPerBar = s.beatsPerBar, segments = s.segments)

    fun showToast(text: String, level: String = "info") {
        toast = ToastMsg(text, level)
    }

    fun clearToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    // ---------------------------------------------------------------- 媒体导入

    fun pickAudio(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { projectRepo.pickAudio(uri) }
            if (picked == null) {
                showToast("音频导入失败", "error")
                return@launch
            }
            snapSnapshot = null
            audioInfo = picked
            runCatching {
                audio.load(picked.file)
                audioReady = true
                showToast("已导入音频：${picked.displayName}", "success")
            }.onFailure {
                audioReady = false
                showToast("音频无法播放：${it.message}", "error")
            }
        }
    }

    fun pickCover(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { projectRepo.pickCover(uri) }
            if (picked == null) {
                showToast("曲绘导入失败", "error")
                return@launch
            }
            coverInfo = picked
            showToast("已导入曲绘", "success")
        }
    }

    fun clearCover() {
        projectRepo.clearCover()
        coverInfo = null
        showToast("已清除曲绘", "info")
    }

    // ---------------------------------------------------------------- 播放

    fun togglePlay() {
        if (!audioReady) {
            showToast("请先导入音频", "warn")
            return
        }
        audio.toggle()
        audioPlaying = audio.isPlaying
    }

    fun stopPlayback() {
        if (!audioReady) return
        audio.pause()
        audio.seekTo(0.0)
        audioPlaying = false
        musicTime = 0.0
    }

    fun seekToMusic(sec: Double) {
        if (!audioReady) return
        audio.seekTo(sec)
        musicTime = sec
    }

    fun refreshTick() {
        musicTime = if (audioReady) audio.currentMusicTimeSec() else musicTime
        audioPlaying = audio.isPlaying
        if (recording) noteCount = coordinator.state.value.noteCount
    }

    // ---------------------------------------------------------------- 录制

    fun startRecording() {
        if (recording) return
        val state = coordinator.state.value
        if (state.phase == RecorderPhase.ARMED) {
            showToast("后台采集已就绪，请返回桌面使用悬浮条开始录制", "warn")
            return
        }
        snapSnapshot = null
        lastGesture = "待机"
        gestureHint = "待机"
        noteCount = 0
        fingerCount = 0
        previewNotes = emptyList()
        stats = null
        coordinator.updateSettings(settings)
        if (audioReady) {
            audio.seekTo(0.0)
            audio.play()
            audioPlaying = true
            musicTime = 0.0
        }
        coordinator.startForeground(displayInfo(), audioBacked = audioReady) { started ->
            if (!started) {
                mainHandler.post {
                    recording = false
                    showToast("无法开始录制，请检查存储空间", "warn")
                }
            }
        }
        recording = true
    }

    fun stopRecording() {
        if (!recording) return
        val state = coordinator.state.value
        if (state.mode == RecordingMode.BACKGROUND) {
            // 后台会话由前台服务持有；直接 stop 会留下通知/悬浮条并丢失预览交接。
            BackgroundCaptureService.stopSession(getApplication())
            return
        }
        if (audioReady) {
            audio.pause()
            audioPlaying = false
        }
        coordinator.stop(StopReason.USER) { result ->
            mainHandler.post {
                recording = false
                noteCount = result.notes.size
                fingerCount = 0
                gestureHint = "已结束"
                rebuildPreview(result.notes)
                showToast("录制结束，共 ${result.notes.size} 个手势", "success")
            }
        }
    }

    fun onTouchFrame(samples: List<TouchSample>, uptimeNanos: Long) {
        if (!recording) return
        val audioTime = if (audioReady) audio.musicTimeSec(uptimeNanos) else null
        coordinator.submitViewFrame(samples, uptimeNanos, audioTime)
        val now = SystemClock.uptimeMillis()
        if (now - lastHudNanos >= 33) {
            lastHudNanos = now
            fingerCount = samples.size
            noteCount = coordinator.state.value.noteCount
            val last = coordinator.lastNote()
            if (last != null) {
                lastGesture = when (last.gesture) {
                    com.touchbridge.rpe.domain.model.Gesture.TAP -> "Tap"
                    com.touchbridge.rpe.domain.model.Gesture.HOLD -> "Hold"
                    com.touchbridge.rpe.domain.model.Gesture.FLICK -> "Flick"
                    com.touchbridge.rpe.domain.model.Gesture.DRAG -> "Drag"
                }
                gestureHint = lastGesture
            }
        }
    }

    private fun displayInfo(): DisplayInfo {
        val metrics = getApplication<Application>().resources.displayMetrics
        return DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, 0)
    }

    // ---------------------------------------------------------------- 预览

    fun rebuildPreview(notes: List<GestureNote> = coordinator.notesSnapshot()) {
        runCatching {
            val result = RpeBuilder.build(notes, clockFor(), settings, meta)
            previewNotes = result.notes
            stats = result.stats
        }.onFailure {
            showToast("谱面生成失败：${it.message}", "error")
        }
    }

    fun loadDemo() {
        val sim = SimulatedSession.generate(
            durationSec = 12.0,
            bpm = settings.bpm,
            offsetMs = settings.offsetMs,
            seed = 42L,
        )
        coordinator.replaceNotes(sim.notes)
        noteCount = sim.notes.size
        rebuildPreview()
        showToast("已注入模拟手势 ${sim.notes.size} 个", "info")
    }

    fun autoCalibrate() {
        val notes = coordinator.notesSnapshot()
        if (notes.isEmpty()) {
            showToast("请先录制或注入手势", "warn")
            return
        }
        val offset = Calibrator.estimate(notes, clockFor())
        if (offset == null) {
            showToast("校准失败", "error")
            return
        }
        updateSettings { it.copy(hitOffsetMs = offset) }
        rebuildPreview()
        showToast("自动校准完成：采样补偿 ${offset} ms", "success")
    }

    private data class SnapSnapshot(val notes: List<GestureNote>, val settings: ChartSettings)

    private var snapSnapshot by mutableStateOf<SnapSnapshot?>(null)
    var snapInProgress by mutableStateOf(false)
        private set
    val canUndoSnap: Boolean get() = snapSnapshot != null && !snapInProgress && !recording

    fun snapRecording(windowMs: Double, strength: Double) {
        if (recording || snapInProgress) return
        val file = audioInfo?.file
        val notes = coordinator.notesSnapshot()
        val previous = settings
        if (file == null || notes.isEmpty()) {
            showToast("请先导入音频并完成录制", "warn")
            return
        }
        if (strength == 0.0 || windowMs == 0.0) {
            showToast("已吸附 0 个手势")
            return
        }
        snapInProgress = true
        viewModelScope.launch {
            try {
                val analysis = AudioAnalyzer.analyze(file)
                require(analysis.points.isNotEmpty()) { "没有检测到可供吸附的音符起点" }
                val result = withContext(Dispatchers.Default) { SnapAligner.align(notes, analysis, windowMs, strength) }
                if (recording || coordinator.notesSnapshot() != notes || settings != previous || audioInfo?.file != file) {
                    showToast("录制或节拍参数已变化，请重新吸附", "warn")
                    return@launch
                }
                if (snapSnapshot == null) snapSnapshot = SnapSnapshot(notes, previous)
                coordinator.replaceNotes(result.notes)
                updateSettings { it.copy(bpm = analysis.bpm, offsetMs = analysis.offsetSec * 1000, segments = emptyList()) }
                rebuildPreview()
                showToast("已吸附 ${result.moved} 个手势", "success")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                showToast(error.message ?: "吸附失败", "error")
            } finally {
                snapInProgress = false
            }
        }
    }

    fun undoSnap() {
        if (!canUndoSnap) return
        val snapshot = snapSnapshot ?: return
        coordinator.replaceNotes(snapshot.notes)
        updateSettings { it.copy(bpm = snapshot.settings.bpm, offsetMs = snapshot.settings.offsetMs, segments = snapshot.settings.segments) }
        snapSnapshot = null
        rebuildPreview()
        showToast("已撤销吸附", "success")
    }

    fun clearNotes() {
        snapSnapshot = null
        coordinator.clearNotes()
        noteCount = 0
        previewNotes = emptyList()
        stats = null
        showToast("已清空音符", "info")
    }

    fun gestureNotes(): List<GestureNote> = coordinator.notesSnapshot()

    // ---------------------------------------------------------------- 导出

    fun exportPez(share: Boolean) {
        if (exporting) return
        viewModelScope.launch {
            exporting = true
            try {
                // 在进入 IO 线程前抓取快照，避免后台线程读取 Compose 状态
                val settingsSnapshot = settings
                val metaSnapshot = meta
                val gestures = coordinator.notesSnapshot()
                val audioSnapshot = audioInfo
                val coverSnapshot = coverInfo
                val outcome = withContext(Dispatchers.IO) {
                    buildPez(settingsSnapshot, metaSnapshot, gestures, audioSnapshot, coverSnapshot)
                }
                lastExport = outcome
                outcome.warning?.let { showToast(it, "warn") }
                if (share) {
                    val intent = exporter.share(File(outcome.path))
                    getApplication<Application>().startActivity(
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    showToast("已准备分享", "success")
                } else {
                    val saved = exporter.saveToDownloads(File(outcome.path), outcome.fileName)
                    lastExport = outcome.copy(path = saved.location)
                    showToast("已保存到 ${saved.location}", "success")
                }
            } catch (e: Exception) {
                showToast("导出失败：${e.message}", "error")
            } finally {
                exporting = false
            }
        }
    }

    private fun buildPez(
        settings: ChartSettings,
        meta: ChartMeta,
        gestures: List<GestureNote>,
        audioInfo: ProjectRepository.PickedFile?,
        coverInfo: ProjectRepository.PickedFile?,
    ): ExportOutcome {
        val result: RpeBuildResult = RpeBuilder.build(gestures, clockFor(settings), settings, meta)
        val audioFile = audioInfo?.file
        val coverFile = coverInfo?.file
        val audioExt = audioInfo?.extension.orEmpty()
        val coverExt = if (coverInfo != null) coverInfo.extension.ifBlank { ".png" } else ".png"
        val length = audioFile?.let { AudioMetaReader.durationSec(it) }
        val pezMeta = InfoFiles.PezMeta(
            name = meta.name.ifBlank { "Untitled" },
            level = meta.level,
            composer = meta.composer,
            charter = meta.charter,
            audioExt = audioExt,
            coverExt = coverExt,
            audioLengthSec = length,
        )
        val tmp = File(getApplication<Application>().cacheDir, "export/${safeName(meta.name)}.pez")
        val packResult = PezPackager().build(
            outFile = tmp,
            rpe = result.rpe,
            meta = pezMeta,
            musicFile = audioFile,
            coverFile = coverFile,
        )
        return ExportOutcome(
            path = packResult.file.absolutePath,
            stats = result.stats,
            fileName = "${safeName(meta.name)}.pez",
            warning = packResult.warning,
        )
    }

    private fun safeName(name: String): String {
        val base = name.ifBlank { "chart" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return base
    }

    override fun onCleared() {
        super.onCleared()
        mainHandler.removeCallbacks(saveSettingsRunnable)
        settingsRepo.saveSettings(settings)
        val state = RecordingCoordinator.state.value
        // 后台录制的生命周期归前台服务所有；仅清理应用内录制，避免划掉任务时误停后台会话。
        if (state.recording && state.mode != RecordingMode.BACKGROUND) {
            coordinator.stop(StopReason.APP_EXIT)
        }
        audio.release()
    }

    fun audioPosition(): Double = if (audioReady) audio.currentMusicTimeSec() else 0.0

    fun audioDuration(): Double = if (audioReady) audio.durationSec else 0.0

    fun hasAudio(): Boolean = audioReady

    fun absDiff(a: Double, b: Double): Double = abs(a - b)
}
