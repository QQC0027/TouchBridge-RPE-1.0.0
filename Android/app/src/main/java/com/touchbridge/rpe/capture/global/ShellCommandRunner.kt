package com.touchbridge.rpe.capture.global

import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** 固定命令的短生命周期执行器；不接收外部拼接的参数。 */
internal object ShellCommandRunner {

    private const val TAG = "ShellCommandRunner"

    fun runShizuku(command: String, timeoutMs: Long = 3_000L): String? {
        if (!ShizukuSetup.isBinderAlive() || !ShizukuSetup.hasPermission()) return null
        return runCatching {
            val process = newShizukuProcess(command)
            readProcess(process, timeoutMs)
        }.onFailure { Log.w(TAG, "Shizuku command failed: $command", it) }.getOrNull()
    }

    /**
     * Shizuku 13.1.5 的 newProcess 是库内 API（不是公开 ABI）。
     * 通过反射调用可避免依赖 UserService/AIDL 样板，同时保持 shell 身份。
     */
    fun newShizukuProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null,
        ) as Process
    }

    fun runRoot(command: String, timeoutMs: Long = 3_000L): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        readProcess(process, timeoutMs)
    }.onFailure { Log.w(TAG, "Root command failed: $command", it) }.getOrNull()

    private fun readProcess(process: Process, timeoutMs: Long): String? {
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < MAX_OUTPUT_CHARS) {
                            output.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            reader.interrupt()
            return null
        }
        reader.join(500)
        return output.toString().takeIf { it.isNotBlank() }
    }

    private const val MAX_OUTPUT_CHARS = 256 * 1024
}
