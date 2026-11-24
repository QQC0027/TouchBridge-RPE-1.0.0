package com.touchbridge.rpe.capture.global

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限与 Binder 状态的统一入口。
 *
 * Shizuku 的权限弹窗由 Shizuku 自己处理，因此请求不需要 Activity 引用；
 * 这里只维护一个可观察状态，供录制页/设置页刷新。
 */
object ShizukuSetup {

    private const val TAG = "ShizukuSetup"
    private const val REQUEST_CODE = 0x5348
    private const val MANAGER_PACKAGE = "moe.shizuku.manager"

    private val _permission = MutableStateFlow(false)
    val permission: StateFlow<Boolean> = _permission.asStateFlow()

    @Volatile
    private var initialized = false

    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Log.i(TAG, "Shizuku binder received")
            refresh()
        }
    }

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            Log.w(TAG, "Shizuku binder dead")
            _permission.value = false
        }
    }

    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == REQUEST_CODE) refresh()
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure { Log.w(TAG, "Shizuku listener registration failed", it) }
        refresh()
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun refresh() {
        _permission.value = hasPermission()
    }

    /** 返回 true 表示已经授权或已弹出授权请求；false 表示 Shizuku 服务未运行。 */
    fun requestPermission(): Boolean {
        if (hasPermission()) {
            _permission.value = true
            return true
        }
        if (!isBinderAlive()) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrElse {
            Log.w(TAG, "Shizuku permission request failed", it)
            false
        }
    }

    fun openManager(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
            return true
        }
        return runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/guide/setup/"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
