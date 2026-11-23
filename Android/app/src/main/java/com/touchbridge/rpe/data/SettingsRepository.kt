package com.touchbridge.rpe.data

import android.content.Context
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.domain.model.ChartSettings
import kotlinx.serialization.json.Json
import java.io.File

/** 参数与谱面元数据持久化（应用私有目录，单个 JSON 文件）。 */
class SettingsRepository(context: Context) {

    private val settingsFile = File(context.filesDir, "settings.json")
    private val metaFile = File(context.filesDir, "meta.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun loadSettings(): ChartSettings {
        return runCatching {
            if (settingsFile.isFile) {
                json.decodeFromString(ChartSettings.serializer(), settingsFile.readText())
            } else {
                ChartSettings()
            }
        }.getOrDefault(ChartSettings())
    }

    fun saveSettings(settings: ChartSettings) {
        runCatching { settingsFile.writeText(json.encodeToString(ChartSettings.serializer(), settings)) }
    }

    fun loadMeta(): ChartMeta {
        return runCatching {
            if (metaFile.isFile) {
                json.decodeFromString(ChartMeta.serializer(), metaFile.readText())
            } else {
                ChartMeta()
            }
        }.getOrDefault(ChartMeta())
    }

    fun saveMeta(meta: ChartMeta) {
        runCatching { metaFile.writeText(json.encodeToString(ChartMeta.serializer(), meta)) }
    }
}
