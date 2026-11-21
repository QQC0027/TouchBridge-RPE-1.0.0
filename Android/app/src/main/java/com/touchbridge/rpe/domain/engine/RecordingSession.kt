package com.touchbridge.rpe.domain.engine

import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TouchSample

/**
 * 录制会话：把采集层、手势分类器、完成音符列表串起来。
 * 纯 Kotlin/无 Android 依赖，可被 JVM 单测直接驱动。
 */
class RecordingSession(private val settingsProvider: () -> ChartSettings) {

    private var nowSec = 0.0
    private val tracker = GestureTracker({ nowSec }, settingsProvider)
    private val collector = CollectorEngine(tracker)

    val notes = mutableListOf<GestureNote>()
    var isRecording = false
        private set
    var startTimeSec = 0.0
        private set
    var lastFrameTimeSec = 0.0
        private set

    fun start(musicTimeSec: Double) {
        notes.clear()
        tracker.reset()
        collector.reset()
        nowSec = musicTimeSec
        startTimeSec = musicTimeSec
        lastFrameTimeSec = musicTimeSec
        isRecording = true
    }

    fun onTouchFrame(samples: List<TouchSample>, frameTimeSec: Double) {
        if (!isRecording) return
        nowSec = frameTimeSec
        lastFrameTimeSec = frameTimeSec
        collector.tick(samples, frameTimeSec)
        drain()
    }

    fun stop(stopTimeSec: Double): List<GestureNote> {
        if (!isRecording) return notes.toList()
        nowSec = stopTimeSec
        lastFrameTimeSec = stopTimeSec
        tracker.flushOpen()
        drain()
        isRecording = false
        return notes.toList()
    }

    fun drain() {
        if (tracker.completed.isNotEmpty()) {
            notes.addAll(tracker.completed)
            tracker.completed.clear()
        }
    }

    fun slotSnapshot(): List<CollectorEngine.SlotView> = collector.snapshot(nowSec)

    /** 注入模拟手势，便于无触摸时验证整条管线。 */
    fun loadSimulated(notesIn: List<GestureNote>) {
        notes.clear()
        notes.addAll(notesIn)
    }
}
