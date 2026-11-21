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
import com.touchbridge.rpe.data.ProjectRepository
import com.touchbridge.rpe.data.SettingsRepository
import com.touchbridge.rpe.domain.engine.BeatClock
import com.touchbridge.rpe.domain.engine.Calibrator
import com.touchbridge.rpe.domain.engine.RecordingSession
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

    private val session = RecordingSession { settings }

    var recording by mutableStateOf(false)
        private set
    var noteCount by mutableStateOf(0)
        private set
    var fingerCount by mutableStateOf(0)
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

    private var lastHudNanos = 0L
    private var virtualStartUptimeMs = 0L
    private var lastGesture: String = "待机"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveSettingsRunnable = Runnable { settingsRepo.saveSettings(settings) }

    init {
        audioInfo?.let { info ->
            runCatching {
                audio.load(info.file)
                audioReady = true
            }
        }
    }

    // ---------------------------------------------------------------- 设置

    fun updateSettings(transform: (ChartSettings) -> ChartSettings) {
        settings = transform(settings)
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
        if (recording) {
            noteCount = session.notes.size
        }
    }

    // ---------------------------------------------------------------- 录制

    fun startRecording() {
        if (recording) return
        lastGesture = "待机"
        gestureHint = "待机"
        noteCount = 0
        fingerCount = 0
        previewNotes = emptyList()
        stats = null
        virtualStartUptimeMs = SystemClock.uptimeMillis()
        if (audioReady) {
            audio.seekTo(0.0)
            audio.play()
            audioPlaying = true
            musicTime = 0.0
        }
        session.start(0.0)
        recording = true
    }

    fun stopRecording() {
        if (!recording) return
        val end = if (audioReady) audio.currentMusicTimeSec() else elapsedVirtual()
        session.stop(end)
        if (audioReady) {
            audio.pause()
            audioPlaying = false
        }
        recording = false
        noteCount = session.notes.size
        fingerCount = 0
        gestureHint = "已结束"
        rebuildPreview()
        showToast("录制结束，共 ${session.notes.size} 个手势", "success")
    }

    fun onTouchFrame(samples: List<TouchSample>, uptimeNanos: Long) {
        if (!recording) return
        val music = if (audioReady) audio.musicTimeSec(uptimeNanos) else elapsedVirtual()
        session.onTouchFrame(samples, music)
        val now = SystemClock.uptimeMillis()
        if (now - lastHudNanos >= 33) {
            lastHudNanos = now
            fingerCount = samples.size
            noteCount = session.notes.size
            val last = session.notes.lastOrNull()
            if (last != null) {
                lastGesture = when (last.gesture) {
                    com.touchbridge.rpe.domain.model.Gesture.TAP -> "Tap"
                    com.touchbridge.rpe.domain.model.Gesture.HOLD -> "Hold"
                    com.touchbridge.rpe.domain.model.Gesture.FLICK -> "Flick"
                    com.touchbridge.rpe.domain.model.Gesture.DRAG -> "Drag"
                }
            }
            gestureHint = lastGesture
        }
    }

    private fun elapsedVirtual(): Double =
        (SystemClock.uptimeMillis() - virtualStartUptimeMs) / 1000.0

    // ---------------------------------------------------------------- 预览

    fun rebuildPreview() {
        runCatching {
            val result = RpeBuilder.build(session.notes, clockFor(), settings, meta)
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
        session.loadSimulated(sim.notes)
        noteCount = sim.notes.size
        rebuildPreview()
        showToast("已注入模拟手势 ${sim.notes.size} 个", "info")
    }

    fun autoCalibrate() {
        if (session.notes.isEmpty()) {
            showToast("请先录制或注入手势", "warn")
            return
        }
        val offset = Calibrator.estimate(session.notes, clockFor())
        if (offset == null) {
            showToast("校准失败", "error")
            return
        }
        updateSettings { it.copy(hitOffsetMs = offset) }
        rebuildPreview()
        showToast("自动校准完成：采样补偿 ${offset} ms", "success")
    }

    fun clearNotes() {
        session.loadSimulated(emptyList())
        noteCount = 0
        previewNotes = emptyList()
        stats = null
        showToast("已清空音符", "info")
    }

    fun gestureNotes(): List<GestureNote> = session.notes.toList()

    // ---------------------------------------------------------------- 导出

    fun exportPez(share: Boolean) {
        if (exporting) return
        viewModelScope.launch {
            exporting = true
            try {
                // 在进入 IO 线程前抓取快照，避免后台线程读取 Compose 状态
                val settingsSnapshot = settings
                val metaSnapshot = meta
                val gestures = session.notes.toList()
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
        audio.release()
    }

    fun audioPosition(): Double = if (audioReady) audio.currentMusicTimeSec() else 0.0

    fun audioDuration(): Double = if (audioReady) audio.durationSec else 0.0

    fun hasAudio(): Boolean = audioReady

    fun absDiff(a: Double, b: Double): Double = abs(a - b)
}
