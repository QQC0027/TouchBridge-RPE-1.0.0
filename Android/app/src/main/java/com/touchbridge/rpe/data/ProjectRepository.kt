package com.touchbridge.rpe.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** 音频/曲绘选择后复制进应用私有目录，避免 SAF 权限失效。 */
class ProjectRepository(private val context: Context) {

    data class PickedFile(val file: File, val displayName: String, val extension: String, val size: Long)

    private val projectDir: File by lazy {
        File(context.filesDir, "project").also { it.mkdirs() }
    }

    fun pickAudio(uri: Uri): PickedFile? = importUri(uri, "audio")

    fun pickCover(uri: Uri): PickedFile? = importUri(uri, "cover")

    private fun importUri(uri: Uri, prefix: String): PickedFile? {
        val name = displayName(uri) ?: "$prefix.bin"
        val ext = name.substringAfterLast('.', "").lowercase()
        val target = File(projectDir, if (ext.isNotEmpty()) "$prefix.$ext" else prefix)
        projectDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("$prefix.")) f.delete()
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            PickedFile(target, name, if (ext.isNotEmpty()) ".$ext" else "", target.length())
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else {
                    null
                }
            }
            ?: uri.lastPathSegment
    }

    fun existingAudio(): PickedFile? = existing("audio")
    fun existingCover(): PickedFile? = existing("cover")

    private fun existing(prefix: String): PickedFile? {
        val f = projectDir.listFiles()?.firstOrNull { it.name.startsWith("$prefix.") } ?: return null
        val ext = f.name.substring(prefix.length)
        return PickedFile(f, f.name, ext, f.length())
    }

    fun clearCover() {
        projectDir.listFiles()?.forEach { if (it.name.startsWith("cover.")) it.delete() }
    }
}
