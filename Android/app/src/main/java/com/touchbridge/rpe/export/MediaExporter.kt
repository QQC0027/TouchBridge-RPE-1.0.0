package com.touchbridge.rpe.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** 把 .pez 保存到系统下载目录（API 29+）或应用外部目录，并支持分享给 Phira/PhiEdit。 */
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
