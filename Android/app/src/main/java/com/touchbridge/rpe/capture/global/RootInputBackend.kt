package com.touchbridge.rpe.capture.global

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.touchbridge.rpe.capture.CaptureSource
import java.io.File
import java.io.IOException

/**
 * su 兜底后端。与 Shizuku 共用 getevent 解析，区别只在于进程启动方式。
 *
 * Root 授权弹窗可能在首次 probe 时出现；因此 probe 应由前台页面触发，
 * 服务启动前只做缓存判断。
 */
class RootInputBackend(
    context: Context,
    private val onError: (Throwable) -> Unit,
) : GlobalTouchBackend {

    override val id: String = "root"
    override val source: CaptureSource = CaptureSource.SHELL

    private val appContext = context.applicationContext
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var parser: GetEventParser? = null

    @Volatile
    private var stopping = false

    override suspend fun isAvailable(): Boolean {
        cachedAvailable?.let { return it }
        return probe()
    }

    override fun start(
        onFrame: (NormalizedTouchFrame) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        stop()
        stopping = false
        readerThread = Thread({
            try {
                val listing = ShellCommandRunner.runRoot("getevent -pl")
                    ?: throw IOException("Root 无法执行 getevent -pl")
                val device = TouchDeviceResolver.parse(listing)
                    ?: throw IOException("未找到可读取的多点触摸设备")
                if (!TouchDeviceResolver.isValidPath(device.path)) {
                    throw IllegalArgumentException("非法的触摸设备路径")
                }
                val p = ProcessBuilder("su", "-c", "getevent -lt ${device.path}")
                    .redirectErrorStream(true)
                    .start()
                process = p
                val localParser = GetEventParser(
                    device = device,
                    rotationProvider = ::currentRotation,
                    timeProvider = { SystemClock.uptimeMillis() },
                    onFrame = onFrame,
                )
                parser = localParser
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stopping) break
                        localParser.consume(line)
                    }
                }
                if (!stopping) throw IOException("getevent 进程已退出")
            } catch (t: Throwable) {
                if (!stopping) {
                    Log.w(TAG, "Root capture stopped", t)
                    onError(t)
                }
            }
        }, "getevent-root").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        stopping = true
        parser = null
        runCatching { process?.destroy() }
        process = null
        runCatching { readerThread?.interrupt() }
        readerThread = null
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = runCatching {
        appContext.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: 0
    }.getOrDefault(0)

    companion object {
        private const val TAG = "RootInputBackend"

        @Volatile
        var cachedAvailable: Boolean? = null
            private set

        fun hasSuBinary(): Boolean {
            if (SU_PATHS.any { path -> runCatching { File(path).isFile }.getOrDefault(false) }) {
                return true
            }
            // Magisk 在部分系统版本上只通过 PATH 暴露 su（如 /debug_ramdisk/su）。
            val which = runCatching {
                val p = ProcessBuilder("sh", "-c", "which su").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            return which.isNotEmpty() && !which.contains("not found")
        }

        suspend fun probe(): Boolean {
            if (!hasSuBinary()) {
                cachedAvailable = false
                return false
            }
            // 首次 probe 可能弹出 Magisk/SuperSU 授权框，给用户留出确认时间。
            val output = ShellCommandRunner.runRoot("getevent -pl", timeoutMs = 15_000L)
            val available = output != null && TouchDeviceResolver.parse(output) != null
            cachedAvailable = available
            return available
        }

        fun invalidate() {
            cachedAvailable = null
        }

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/debug_ramdisk/su",
        )
    }
}
