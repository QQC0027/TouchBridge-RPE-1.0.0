package com.touchbridge.rpe.capture

import com.touchbridge.rpe.domain.model.GestureNote
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 启动时扫描未正常结束的录制会话。
 *
 * 日志是 append-only 的 NDJSON：崩溃或断电只会损坏最后一行，因此解析到第一行
 * 损坏即截断保留前缀；`seq` 用于幂等去重。
 */
object RecordingRecovery {

    data class RecoveredSession(
        val directory: File,
        val header: SessionHeader,
        val notes: List<GestureNote>,
        val ended: Boolean,
        val endReason: String?,
        val endTimeSec: Double?,
        val durationSec: Double,
        val lastWriteMs: Long,
        val damagedTail: Boolean,
    ) {
        val noteCount: Int get() = notes.size
        val sessionId: String get() = header.sessionId
    }

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun scan(root: File): List<RecoveredSession> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> runCatching { load(dir) }.getOrNull() }
            .sortedByDescending { it.header.createdAtMs }
    }

    fun pending(root: File): List<RecoveredSession> = scan(root).filter { !it.ended }

    /** 最近一次正常结束、有手势数据的会话；用于交接标记丢失时重建预览。 */
    fun latestCompleted(root: File): RecoveredSession? =
        scan(root).firstOrNull { it.ended && it.notes.isNotEmpty() }

    fun load(dir: File): RecoveredSession? {
        val headerFile = File(dir, RecordingJournal.HEADER_NAME)
        if (!headerFile.isFile) return null
        val header = runCatching {
            JSON.decodeFromString(SessionHeader.serializer(), headerFile.readText(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null

        val journal = File(dir, RecordingJournal.JOURNAL_NAME)
        if (!journal.isFile) {
            return RecoveredSession(dir, header, emptyList(), false, null, null, 0.0, 0L, false)
        }

        val notes = mutableListOf<GestureNote>()
        val seenSeq = HashSet<Long>()
        var ended = false
        var endReason: String? = null
        var endTime: Double? = null
        var lastAt = 0.0
        var damaged = false

        journal.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = runCatching {
                    JSON.decodeFromString(JournalRecord.serializer(), line)
                }.getOrNull() ?: run {
                    damaged = true
                    return@useLines
                }
                if (!seenSeq.add(record.seq)) continue
                lastAt = maxOf(lastAt, record.at)
                when (record.type) {
                    RecordingJournal.TYPE_NOTE -> record.note?.let { JournalCodec.decode(it) }?.let(notes::add)
                    RecordingJournal.TYPE_END -> {
                        ended = true
                        endReason = record.reason
                        endTime = record.at
                    }
                }
            }
        }

        val duration = when {
            ended -> endTime ?: lastAt
            notes.isNotEmpty() -> notes.maxOf { it.tSec + it.holdSec }
            else -> 0.0
        }
        return RecoveredSession(
            directory = dir,
            header = header,
            notes = notes,
            ended = ended,
            endReason = endReason,
            endTimeSec = endTime,
            durationSec = maxOf(duration, lastAt),
            lastWriteMs = journal.lastModified(),
            damagedTail = damaged,
        )
    }

    /** 只保留最近 [keep] 个会话目录，避免日志无限增长。 */
    fun prune(root: File, keep: Int = 20) {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return
        dirs.sortedByDescending { it.lastModified() }.drop(keep).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    fun delete(root: File, sessionId: String): Boolean =
        runCatching { File(root, sessionId).deleteRecursively() }.getOrDefault(false)
}
