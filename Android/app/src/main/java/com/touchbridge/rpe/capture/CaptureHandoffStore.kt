package com.touchbridge.rpe.capture

import android.content.Context

/** 持久化“刚结束、等待预览”的会话 ID，用于进程被杀后的兜底。 */
object CaptureHandoffStore {

    private const val PREFS = "capture_handoff"
    private const val KEY_SESSION = "pending_session"

    fun markPending(context: Context, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        // 结果交接要求进程随时可能被杀，必须同步落盘而不是 apply()。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION, sessionId)
            .commit()
    }

    fun takePending(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = prefs.getString(KEY_SESSION, null)
        if (sessionId != null) prefs.edit().remove(KEY_SESSION).commit()
        return sessionId
    }
}
