package com.touchbridge.rpe.capture

import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.domain.model.GestureNote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 日志与恢复的纯 JVM 验收：
 * 1. 正常停止后能完整读回手势与 end 记录；
 * 2. 崩溃造成的半行不会破坏已写入前缀；
 * 3. 重复 seq 幂等；
 * 4. 已结束的会话不进入恢复列表。
 */
class RecordingJournalTest {

    private val root: File = Files.createTempDirectory("rpe-journal").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun header(id: String) = SessionHeader(
        sessionId = id,
        createdAtMs = 1_700_000_000_000,
        timebase = SessionHeader.TIMEBASE_MONOTONIC,
        source = SessionHeader.SOURCE_BACKGROUND,
    )

    private fun notes(count: Int): List<GestureNote> = (0 until count).map { index ->
        GestureNote(
            gesture = Gesture.TAP,
            finger = 0,
            tSec = 1.0 + index,
            x = 0.5,
            holdSec = 0.0,
            gid = index,
        )
    }

    @Test
    fun `round trip keeps notes and end record`() {
        val journal = RecordingJournal.create(root, header("s1"))
        notes(3).forEach { journal.appendNote(it) }
        journal.appendMarker("STOP_USER", 4.0)
        journal.finish("user", 4.0)
        journal.close()

        val recovered = RecordingRecovery.load(journal.directory)!!
        assertEquals(3, recovered.noteCount)
        assertTrue(recovered.ended)
        assertEquals("user", recovered.endReason)
        assertFalse(recovered.damagedTail)
        assertEquals(Gesture.TAP, recovered.notes.first().gesture)
        assertEquals(2, recovered.notes.last().gid)
        assertEquals(4.0, recovered.durationSec, 0.0001)
    }

    @Test
    fun `truncated tail keeps the written prefix`() {
        val journal = RecordingJournal.create(root, header("s2"))
        notes(2).forEach { journal.appendNote(it) }
        journal.appendMarker("GAP", 2.5)
        journal.close()

        journal.file.appendText("{\"type\":\"note\",\"seq\":99")

        val recovered = RecordingRecovery.load(journal.directory)!!
        assertEquals(2, recovered.noteCount)
        assertTrue(recovered.damagedTail)
        assertFalse(recovered.ended)
        assertEquals(listOf("s2"), RecordingRecovery.pending(root).map { it.sessionId })
    }

    @Test
    fun `duplicated seq is applied once`() {
        val journal = RecordingJournal.create(root, header("s3"))
        journal.appendNote(notes(1).first())
        journal.close()

        val first = journal.file.readLines().first { it.contains("\"type\":\"note\"") }
        journal.file.appendText(first + "\n")

        val recovered = RecordingRecovery.load(journal.directory)!!
        assertEquals(1, recovered.noteCount)
        assertFalse(recovered.damagedTail)
    }

    @Test
    fun `pending skips finished sessions`() {
        val finished = RecordingJournal.create(root, header("done"))
        finished.appendNote(notes(1).first())
        finished.finish("user", 2.0)
        finished.close()

        val open = RecordingJournal.create(root, header("open"))
        open.appendNote(notes(1).first())
        open.close()

        assertEquals(listOf("open"), RecordingRecovery.pending(root).map { it.sessionId })
    }

    @Test
    fun `delete removes the session directory`() {
        val journal = RecordingJournal.create(root, header("s4"))
        journal.appendNote(notes(1).first())
        journal.close()

        assertTrue(RecordingRecovery.delete(root, "s4"))
        assertFalse(journal.directory.exists())
        assertTrue(RecordingRecovery.pending(root).isEmpty())
    }
}
