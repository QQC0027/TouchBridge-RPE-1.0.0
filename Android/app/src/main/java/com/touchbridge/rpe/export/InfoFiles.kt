package com.touchbridge.rpe.export

import java.time.LocalDateTime

/** 生成 PEZ 内的 info.txt（RPE 桌面版）与 info.yml（Phira）。纯 JVM，可单测。 */
object InfoFiles {

    data class PezMeta(
        val name: String,
        val level: String,
        val composer: String,
        val charter: String,
        val audioExt: String,
        val coverExt: String,
        val audioLengthSec: Double?,
    )

    fun infoTxt(pid: String, meta: PezMeta): String {
        val now = LocalDateTime.now()
        val lastEdit = buildString {
            append(now.year)
            append('_').append(now.monthValue.toString().padStart(2, '0'))
            append('_').append(now.dayOfMonth.toString().padStart(2, '0'))
            append('_').append(now.hour.toString().padStart(2, '0'))
            append('_').append(now.minute.toString().padStart(2, '0'))
            append('_').append(now.second.toString().padStart(2, '0'))
            append('_')
        }
        val level = meta.level.trim().toIntOrNull() ?: 0
        val lines = mutableListOf(
            "#",
            "Name: ${meta.name}",
            "Path: $pid",
            "Song: $pid${meta.audioExt}",
            "Picture: $pid${meta.coverExt}",
            "Chart: $pid.json",
            "Level: $level",
            "Composer: ${meta.composer}",
            "Charter: ${meta.charter.ifBlank { "TouchBridge" }}",
            "LastEditTime: $lastEdit",
        )
        meta.audioLengthSec?.let { lines.add("Length: $it") }
        lines.add("EditTime: 0.0")
        lines.add("Group: Default")
        return lines.joinToString("\r\n") + "\r\n"
    }

    fun infoYml(pid: String, meta: PezMeta, hasMusic: Boolean): String {
        val fields = linkedMapOf<String, Any>(
            "name" to meta.name,
            "difficulty" to 0,
            "level" to meta.level.ifBlank { "SP" },
            "charter" to meta.charter.ifBlank { "TouchBridge" },
            "composer" to meta.composer,
            "illustrator" to "",
            "chart" to "$pid.json",
            "format" to "rpe",
            "music" to if (hasMusic) "$pid${meta.audioExt}" else "",
            "illustration" to "$pid${meta.coverExt}",
        )
        return fields.entries.joinToString("\n") { (k, v) ->
            if (v is String) "$k: ${yamlStr(v)}" else "$k: $v"
        } + "\n"
    }

    /** JSON 字符串是合法 YAML 标量（等价于 Python json.dumps(ensure_ascii=False)）。 */
    fun yamlStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }
}
