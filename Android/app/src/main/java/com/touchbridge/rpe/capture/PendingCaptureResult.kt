package com.touchbridge.rpe.capture

import com.touchbridge.rpe.domain.model.GestureNote
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台录制结束后的进程内交接。
 *
 * 服务先写入结果，再拉起 MainActivity；ViewModel 只消费一次。进程被杀时
 * 该对象会丢失，由 RecordingRecovery 的“最近已完成会话”兜底。
 */
data class CaptureHandoff(
    val sessionId: String?,
    val notes: List<GestureNote>,
    val durationSec: Double,
    val reason: String,
)

object PendingCaptureResult {

    private val pending = AtomicReference<CaptureHandoff?>(null)

    fun publish(result: RecorderResult, reason: StopReason) {
        pending.set(
            CaptureHandoff(
                sessionId = result.sessionId,
                notes = result.notes.toList(),
                durationSec = result.durationSec,
                reason = reason.name,
            ),
        )
    }

    fun take(): CaptureHandoff? = pending.getAndSet(null)

    fun peek(): CaptureHandoff? = pending.get()
}
