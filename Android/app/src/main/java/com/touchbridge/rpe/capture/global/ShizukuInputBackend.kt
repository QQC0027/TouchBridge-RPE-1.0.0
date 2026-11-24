package com.touchbridge.rpe.capture.global

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.touchbridge.rpe.capture.CaptureSource
import rikka.shizuku.Shizuku
import java.io.IOException

/**
 * 以 Shizuku（shell 或 root 身份）执行 getevent。
 *
 * 构造后不会阻塞；[isAvailable] 会实际执行一次 `getevent -pl` 探针，
 * 以确认当前 SELinux/设备权限是否真的允许读取触摸设备。
 */
class ShizukuInputBackend(
    context: Context,
    private val onError: (Throwable) -> Unit,
) : GlobalTouchBackend {

    override val id: String = "shizuku"
    override val source: CaptureSource = CaptureSource.SHELL

    private val appContext = context.applicationContext
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var parser: GetEventParser? = null

    @Volatile
    private var stopping = false

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            if (!stopping) onError(IOException("Shizuku 服务已断开"))
        }
    }

    init {
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
    }

    override suspend fun isAvailable(): Boolean {
        if (!ShizukuSetup.hasPermission()) return false
        val output = ShellCommandRunner.runShizuku("getevent -pl") ?: return false
        return TouchDeviceResolver.parse(output) != null
    }

    override fun start(
        onFrame: (NormalizedTouchFrame) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        stop()
        stopping = false
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
        readerThread = Thread({
            try {
                val listing = ShellCommandRunner.runShizuku("getevent -pl")
                    ?: throw IOException("Shizuku 无法执行 getevent -pl")
                val device = TouchDeviceResolver.parse(listing)
                    ?: throw IOException("未找到可读取的多点触摸设备")
                if (!TouchDeviceResolver.isValidPath(device.path)) {
                    throw IllegalArgumentException("非法的触摸设备路径")
                }
                val command = "getevent -lt ${device.path}"
                val p = ShellCommandRunner.newShizukuProcess(command)
                process = p
                // 线程持有自己的 parser，避免 stop/start 后旧线程把数据喂给新 parser。
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
                    Log.w(TAG, "Shizuku capture stopped", t)
                    onError(t)
                }
            }
        }, "getevent-shizuku").apply {
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
        runCatching { Shizuku.removeBinderDeadListener(binderDead) }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = runCatching {
        appContext.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: 0
    }.getOrDefault(0)

    private companion object {
        const val TAG = "ShizukuInputBackend"
    }
}
