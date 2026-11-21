package com.touchbridge.rpe.export

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PezPackagerTest {

    private val meta = InfoFiles.PezMeta(
        name = "测试谱",
        level = "IN Lv.15",
        composer = "测试作曲",
        charter = "测试谱师",
        audioExt = ".mp3",
        coverExt = ".png",
        audioLengthSec = 12.5,
    )

    private fun sampleRpe(): JsonObject = Json.parseToJsonElement(
        """
        {
          "BPMList": [{"bpm": 120.0, "startTime": [0, 0, 1]}],
          "META": {
            "offset": 0,
            "song": "old.mp3",
            "background": "old.png",
            "illustration": "old.png",
            "name": "旧名字",
            "composer": "旧作曲",
            "charter": "旧谱师",
            "level": "旧难度"
          },
          "judgeLineList": []
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `pez 内条目平铺且元数据被改写`() {
        val dir = Files.createTempDirectory("pez-test").toFile()
        val cover = File(dir, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val music = File(dir, "music.mp3").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        val out = File(dir, "out.pez")

        val result = PezPackager().build(
            outFile = out,
            rpe = sampleRpe(),
            meta = meta,
            musicFile = music,
            coverFile = cover,
            pid = "1234567890123456",
        )

        assertEquals(null, result.warning)
        assertTrue(out.exists() && out.length() > 0)

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf("info.txt", "info.yml", "1234567890123456.json", "1234567890123456.png", "1234567890123456.mp3")
                    .sorted(),
                names.sorted(),
            )
            // 所有条目都在根目录，不能带子目录
            assertTrue(names.none { it.contains('/') || it.contains('\\') })

            val chart = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("1234567890123456.json")).readBytes().toString(Charsets.UTF_8),
            ).jsonObject["META"]!!.jsonObject
            assertEquals("1234567890123456.mp3", chart["song"]!!.jsonPrimitive.content)
            assertEquals("1234567890123456.png", chart["background"]!!.jsonPrimitive.content)
            assertEquals("1234567890123456.png", chart["illustration"]!!.jsonPrimitive.content)
            // 打包层只改写歌曲/曲绘文件名，其余 META 由 RpeBuilder 使用同一份 meta 写入
            assertEquals("旧名字", chart["name"]!!.jsonPrimitive.content)
            assertEquals("旧难度", chart["level"]!!.jsonPrimitive.content)

            val infoTxt = zip.getInputStream(zip.getEntry("info.txt")).readBytes().toString(Charsets.UTF_8)
            assertTrue(infoTxt.startsWith("#"))
            assertTrue(infoTxt.contains("\r\n"))
            assertTrue(infoTxt.contains("测试谱"))

            val infoYml = zip.getInputStream(zip.getEntry("info.yml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(infoYml.contains("chart: \"1234567890123456.json\""))
            assertTrue(infoYml.contains("music: \"1234567890123456.mp3\""))
            assertTrue(infoYml.contains("illustration: \"1234567890123456.png\""))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `无音频时警告且不写音乐条目`() {
        val dir = Files.createTempDirectory("pez-test2").toFile()
        val out = File(dir, "out.pez")
        val result = PezPackager().build(
            outFile = out,
            rpe = sampleRpe(),
            meta = meta.copy(audioExt = ""),
            musicFile = null,
            coverFile = null,
            pid = "0000000000000001",
        )
        assertTrue(result.warning != null)
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("0000000000000001.json", "0000000000000001.png", "info.txt", "info.yml"), names)
            val chart = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("0000000000000001.json")).readBytes().toString(Charsets.UTF_8),
            ).jsonObject["META"]!!.jsonObject
            // 无音频时保留原始 song 字段，不写空文件名
            assertEquals("old.mp3", chart["song"]!!.jsonPrimitive.content)
        }
        dir.deleteRecursively()
    }
}
