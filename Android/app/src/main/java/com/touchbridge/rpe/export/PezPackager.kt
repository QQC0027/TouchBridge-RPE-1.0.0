package com.touchbridge.rpe.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * 生成通用 .pez（zip）：谱面 json、音乐、曲绘、info.txt、info.yml 全部平铺在根目录。
 * 纯 JVM 实现，便于单元测试；Android 端只负责提供文件与落盘位置。
 */
class PezPackager {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    data class Result(val file: File, val pid: String, val warning: String?)

    fun createPid(): String {
        val raw = System.currentTimeMillis() * 1000L + Random.nextInt(0, 1000)
        return raw.toString().padStart(16, '0').takeLast(16)
    }

    fun build(
        outFile: File,
        rpe: JsonObject,
        meta: InfoFiles.PezMeta,
        musicFile: File?,
        coverFile: File?,
        pid: String = createPid(),
    ): Result {
        val chartObj = rewriteMeta(rpe, pid, meta)
        val chartJson = json.encodeToString(JsonObject.serializer(), chartObj)

        val cover = coverFile ?: File.createTempFile("tb_cover_", ".png").also { f ->
            f.outputStream().use { PlaceholderCover.writePng(it, 512) }
        }
        val coverExt = meta.coverExt.ifBlank { ".png" }

        outFile.parentFile?.mkdirs()
        ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
            fun putText(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            putText("info.txt", InfoFiles.infoTxt(pid, meta))
            putText("info.yml", InfoFiles.infoYml(pid, meta, musicFile != null))
            putText("$pid.json", chartJson)

            zos.putNextEntry(ZipEntry("$pid$coverExt"))
            cover.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            if (musicFile != null) {
                zos.putNextEntry(ZipEntry("$pid${meta.audioExt}"))
                musicFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        if (coverFile == null) cover.delete()

        return Result(
            file = outFile,
            pid = pid,
            warning = if (musicFile == null) "未导入音频，包内暂无音乐文件" else null,
        )
    }

    private fun rewriteMeta(rpe: JsonObject, pid: String, meta: InfoFiles.PezMeta): JsonObject {
        val oldMeta = rpe["META"]?.jsonObject ?: buildJsonObject { }
        val newMeta = buildJsonObject {
            for ((k, v) in oldMeta) {
                if (k == "illustration" || k == "background") {
                    put(k, JsonPrimitive("$pid${meta.coverExt}"))
                } else if (k == "song" && meta.audioExt.isNotBlank()) {
                    put(k, JsonPrimitive("$pid${meta.audioExt}"))
                } else {
                    put(k, v)
                }
            }
        }
        return buildJsonObject {
            for ((k, v) in rpe) {
                if (k == "META") put(k, newMeta) else put(k, v)
            }
        }
    }
}
