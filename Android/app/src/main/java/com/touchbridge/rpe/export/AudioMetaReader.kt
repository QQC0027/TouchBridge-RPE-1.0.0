package com.touchbridge.rpe.export

import android.media.MediaMetadataRetriever
import java.io.File

/** 读取音频时长（info.txt 的 Length 字段）。 */
object AudioMetaReader {
    fun durationSec(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.let { it / 1000.0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
