package com.touchbridge.rpe.capture

import com.touchbridge.rpe.domain.model.ChartSettings
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import com.touchbridge.rpe.domain.model.TrackPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class JournalPoint(val t: Double, val x: Double, val y: Double)

@Serializable
data class JournalNote(
    val gesture: String,
    val finger: Int,
    val t: Double,
    val x: Double,
    val hold: Double = 0.0,
    val path: List<JournalPoint> = emptyList(),
    val dirX: Double? = null,
    val dirY: Double? = null,
    val gid: Int = 0,
)

@Serializable
data class JournalRecord(
    val type: String,
    val seq: Long = 0,
    val at: Double = 0.0,
    val note: JournalNote? = null,
    val marker: String? = null,
    val reason: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
)

@Serializable
data class SessionHeader(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val createdAtMs: Long,
    val timebase: String,
    val source: String,
    val appVersion: String = "",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val densityDpi: Int = 0,
    val rotation: Int = 0,
    val settings: ChartSettings = ChartSettings(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val TIMEBASE_MONOTONIC = "MONOTONIC"
        const val TIMEBASE_AUDIO = "AUDIO"
        const val SOURCE_BACKGROUND = "BACKGROUND"
        const val SOURCE_VIEW_ONLY = "VIEW_ONLY"
    }
}

/**
 * 增量录制日志：append-only NDJSON，单写者。
 *
 * - 每条已结算手势一行；最多缓冲 [MAX_BUFFERED_RECORDS] 条或 [SYNC_INTERVAL_MS] 毫秒
 *   触发一次 `flush + fd.sync()`，进程被杀时最坏丢失约 1 秒。
 * - 停止时写入 `end` 记录并落盘，下次启动据此判断是否需要恢复。
 * - 纯 JVM 实现，不依赖 Android，可直接单测。
 */
class RecordingJournal private constructor(
    val directory: File,
    private val header: SessionHeader,
    private val onError: ((IOException) -> Unit)?,
) : Closeable {

    private val journalFile = File(directory, JOURNAL_NAME)
    private val stream = FileOutputStream(journalFile, true)
    private val writer = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8), 16 * 1024)

    private var seq = 0L
    private var buffered = 0
    private var lastSyncMs = System.currentTimeMillis()
    private var bytes = 0L
    private var closed = false
    var failure: IOException? = null
        private set

    val file: File get() = journalFile
    val sessionId: String get() = header.sessionId
    val headerSnapshot: SessionHeader get() = header

    fun appendNote(note: GestureNote): Boolean = append(JournalRecord(type = TYPE_NOTE, note = JournalCodec.encode(note)))

    fun appendMarker(marker: String, at: Double): Boolean =
        append(JournalRecord(type = TYPE_MARKER, at = at, marker = marker))

    fun appendGeometry(width: Int, height: Int, rotation: Int, at: Double): Boolean =
        append(JournalRecord(type = TYPE_GEOMETRY, at = at, width = width, height = height, rotation = rotation))

    fun finish(reason: String, at: Double): Boolean {
        val ok = append(JournalRecord(type = TYPE_END, at = at, reason = reason), force = true)
        runCatching { writer.flush(); stream.fd.sync() }
        return ok
    }

    private fun append(record: JournalRecord, force: Boolean = false): Boolean {
        if (closed || failure != null) return false
        return try {
            seq += 1
            val line = JSON.encodeToString(JournalRecord.serializer(), record.copy(seq = seq))
            writer.write(line)
            writer.write("\n")
            buffered += 1
            bytes += line.length + 1L
            maybeSync(force)
            true
        } catch (error: IOException) {
            failure = error
            onError?.invoke(error)
            false
        }
    }

    private fun maybeSync(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && buffered < MAX_BUFFERED_RECORDS && now - lastSyncMs < SYNC_INTERVAL_MS) return
        writer.flush()
        stream.fd.sync()
        buffered = 0
        lastSyncMs = now
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer.flush() }
        runCatching { stream.fd.sync() }
        runCatching { writer.close() }
        runCatching { stream.close() }
    }

    companion object {
        const val JOURNAL_NAME = "journal.ndjson"
        const val HEADER_NAME = "session.json"
        const val MAX_BUFFERED_RECORDS = 32
        const val SYNC_INTERVAL_MS = 1000L
        const val TYPE_NOTE = "note"
        const val TYPE_MARKER = "marker"
        const val TYPE_GEOMETRY = "geometry"
        const val TYPE_END = "end"

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun newSessionId(nowMs: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
            val salt = (nowMs % 1000).toString().padStart(3, '0')
            return "$stamp-$salt"
        }

        fun create(
            root: File,
            header: SessionHeader,
            onError: ((IOException) -> Unit)? = null,
        ): RecordingJournal {
            val dir = File(root, header.sessionId).apply { mkdirs() }
            val headerFile = File(dir, HEADER_NAME)
            if (!headerFile.exists()) {
                val tmp = File(dir, "$HEADER_NAME.tmp")
                tmp.writeText(JSON.encodeToString(SessionHeader.serializer(), header), StandardCharsets.UTF_8)
                tmp.renameTo(headerFile)
            }
            return RecordingJournal(dir, header, onError)
        }
    }
}

object JournalCodec {

    fun encode(note: GestureNote): JournalNote = JournalNote(
        gesture = note.gesture.name,
        finger = note.finger,
        t = note.tSec,
        x = note.x,
        hold = note.holdSec,
        path = note.path.map { JournalPoint(it.t, it.x, it.y) },
        dirX = note.dir?.first,
        dirY = note.dir?.second,
        gid = note.gid,
    )

    fun decode(note: JournalNote): GestureNote? {
        val gesture = Gesture.entries.firstOrNull { it.name.equals(note.gesture, ignoreCase = true) } ?: return null
        return GestureNote(
            gesture = gesture,
            finger = note.finger,
            tSec = note.t,
            x = note.x,
            holdSec = note.hold,
            path = note.path.map { TrackPoint(it.t, it.x, it.y) },
            dir = if (note.dirX != null && note.dirY != null) note.dirX to note.dirY else null,
            gid = note.gid,
        )
    }
}
