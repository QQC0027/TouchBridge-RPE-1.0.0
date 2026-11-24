package com.touchbridge.rpe.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** 把 .pez 保存到系统下载目录（API 29+ MediaStore，API 26–28 公共 Downloads），并支持分享。 */
class MediaExporter(private val context: Context) {

    data class Saved(val uri: Uri?, val file: File, val location: String)

    fun saveToDownloads(src: File, displayName: String): Saved {
        val safeName = sanitize(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TouchBridge")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return Saved(uri, src, "Download/TouchBridge/$safeName")
            }
        }
        // API 26–28：已授予旧存储权限时写入公共 Downloads，和 29+ 的用户可见位置一致。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                @Suppress("DEPRECATION")
                val publicDownloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                val targetDir = File(publicDownloads, "TouchBridge")
                targetDir.mkdirs()
                val target = File(targetDir, safeName)
                src.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                return Saved(null, target, "Download/TouchBridge/$safeName")
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        dir.mkdirs()
        val target = File(dir, safeName)
        src.copyTo(target, overwrite = true)
        return Saved(null, target, target.absolutePath)
    }

    fun share(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "分享谱面").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun sanitize(name: String): String {
        val base = name.ifBlank { "chart" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (base.endsWith(".pez")) base else "$base.pez"
    }
}
