package com.touchbridge.rpe.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.touchbridge.rpe.data.ProjectRepository.PickedFile
import com.touchbridge.rpe.domain.autochart.AutoChartConfig
import com.touchbridge.rpe.domain.autochart.AutoChartEngine
import com.touchbridge.rpe.domain.autochart.AutoChartOptions
import com.touchbridge.rpe.domain.autochart.AutoChartOutput
import com.touchbridge.rpe.domain.autochart.AutoChartResult
import com.touchbridge.rpe.domain.model.ChartMeta
import com.touchbridge.rpe.export.AudioMetaReader
import com.touchbridge.rpe.export.InfoFiles
import com.touchbridge.rpe.export.PezPackager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AutoChartViewModel(app: Application) : AndroidViewModel(app) {
    data class InputFile(val file: File, val name: String, val extension: String)
    data class Progress(val percent: Int?, val stage: String, val canCancel: Boolean = false)
    data class Completed(val output: AutoChartOutput, val audio: InputFile, val cover: InputFile?, val meta: ChartMeta)
    enum class InputKind(val label: String, val fallbackExtension: String) {
        Audio("音频", ".mp3"), Cover("曲绘", ".png"),
    }
    sealed interface Export {
        data class Tier(val tier: String) : Export
        data object AllTiers : Export
        data object Points : Export
    }

    private data class Points(val name: String, val json: String)
    private data class Request(
        val audio: InputFile,
        val cover: InputFile?,
        val meta: ChartMeta,
        val options: AutoChartOptions,
        val pointsJson: String?,
    )

    private val resolver = app.contentResolver
    private val workDir = File(app.cacheDir, "autochart")
    private val importedFiles = mutableSetOf<File>()
    private var job: Job? = null
    private var importedPoints by mutableStateOf<Points?>(null)
    private var pendingRequest: Request? = null
    private var pendingExport: Export? = null

    var selectedAudio by mutableStateOf<InputFile?>(null)
        private set
    var selectedCover by mutableStateOf<InputFile?>(null)
        private set
    var useProjectCover by mutableStateOf(true)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var longTrackSeconds by mutableStateOf<Double?>(null)
        private set
    var completed by mutableStateOf<Completed?>(null)
        private set

    val pointsName: String? get() = importedPoints?.name

    fun pickMedia(uri: Uri, kind: InputKind) = runTask("正在导入${kind.label}") {
        var picked: InputFile? = null
        try {
            picked = withContext(Dispatchers.IO) { copyMedia(uri, kind) }
            val imported = requireNotNull(picked)
            importedFiles += imported.file
            when (kind) {
                InputKind.Audio -> selectedAudio = imported
                InputKind.Cover -> selectedCover = imported
            }
            picked = null
            removeUnusedImports()
        } finally {
            picked?.file?.delete()
        }
    }

    fun useCurrentAudio() {
        if (progress != null) return
        selectedAudio = null
        removeUnusedImports()
    }

    fun setCoverSource(project: Boolean) {
        if (progress != null) return
        selectedCover = null
        useProjectCover = project
        removeUnusedImports()
    }

    fun pickPoints(uri: Uri) = runTask("正在读取踩音文件") {
        importedPoints = withContext(Dispatchers.IO) {
            val name = displayName(uri, "points.json")
            val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开踩音文件" }.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureActive()
                    val length = stream.read(buffer)
                    if (length < 0) break
                    require(output.size() + length <= MAX_POINTS_BYTES) { "踩音文件不能超过 16 MB" }
                    output.write(buffer, 0, length)
                }
                output.toByteArray()
            }
            val json = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            AutoChartEngine.readPoints(json)
            Points(name, json)
        }
    }

    fun clearPoints() {
        if (progress == null) importedPoints = null
    }

    fun requestGeneration(audio: PickedFile?, cover: PickedFile?, meta: ChartMeta, options: AutoChartOptions) {
        if (progress != null || longTrackSeconds != null) return
        val source = selectedAudio ?: audio?.asInput(InputKind.Audio)
        if (source == null) {
            error = "请先选择音频"
            return
        }
        val artwork = selectedCover ?: cover?.takeIf { useProjectCover }?.asInput(InputKind.Cover)
        val request = Request(source, artwork, meta, options, importedPoints?.json)
        runTask("正在读取音频信息", canCancel = true) {
            val duration = withContext(Dispatchers.IO) { AudioMetaReader.durationSec(source.file) }
            if (duration != null && duration > MAX_TRACK_SECONDS) {
                error = "音频超过 30 分钟的分析上限"
            } else if (duration != null && duration >= LONG_TRACK_CONFIRM_SECONDS) {
                pendingRequest = request
                longTrackSeconds = duration
            } else {
                generate(request)
            }
        }
    }

    fun answerLongTrack(confirmed: Boolean) {
        val request = pendingRequest
        pendingRequest = null
        longTrackSeconds = null
        if (confirmed && request != null) runTask("正在准备自动制谱", canCancel = true) { generate(request) }
    }

    fun cancel() {
        if (progress?.canCancel == true) {
            progress = Progress(null, "正在取消")
            job?.cancel()
        }
    }

    private suspend fun generate(request: Request) = coroutineScope {
        val updates = MutableStateFlow(Progress(0, "正在准备自动制谱", canCancel = true))
        val observer = launch { updates.collect { progress = it } }
        val output = try {
            withContext(Dispatchers.Default) {
                AutoChartEngine.generate(request.audio.file, request.meta, request.options, request.pointsJson) { percent, stage ->
                    ensureActive()
                    updates.value = Progress(percent.coerceIn(0, 100), stage, canCancel = true)
                }
            }
        } finally {
            observer.cancel()
        }
        completed = Completed(output, request.audio, request.cover, request.meta)
        removeUnusedImports()
        message = "制谱完成，共生成 ${output.charts.size} 份谱面"
    }

    fun prepareExport(export: Export): String? {
        val result = completed?.takeIf { progress == null } ?: return null
        val fileName = when (export) {
            is Export.Tier -> result.output.charts.find { it.tier == export.tier }?.let { "${it.chartId}.pez" }
            Export.AllTiers -> "charts.zip".takeIf { result.output.charts.size > 1 }
            Export.Points -> "points.json"
        }
        pendingExport = export.takeIf { fileName != null }
        return fileName
    }

    fun finishExport(uri: Uri?) {
        val export = pendingExport.also { pendingExport = null }
        if (export == null) {
            if (uri != null) error = "导出任务已失效，请重新选择导出"
            return
        }
        val result = completed
        if (result == null) {
            if (uri != null) error = "制谱结果已失效，请重新生成后导出"
            return
        }
        if (uri == null) return
        runTask("正在导出") {
            withContext(Dispatchers.IO) {
                workDir.mkdirs()
                val temp = File.createTempFile("export-", ".tmp", workDir)
                try {
                    when (export) {
                        is Export.Tier -> buildPez(result, result.output.charts.first { it.tier == export.tier }, temp)
                        Export.AllTiers -> {
                            val pez = File.createTempFile("tier-", ".pez", workDir)
                            try {
                                ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                                    zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                                    for (chart in result.output.charts) {
                                        ensureActive()
                                        buildPez(result, chart, pez)
                                        zip.putNextEntry(ZipEntry("${chart.chartId}.pez"))
                                        pez.inputStream().use { stream -> copy(stream, zip) { ensureActive() } }
                                        zip.closeEntry()
                                    }
                                }
                            } finally {
                                pez.delete()
                            }
                        }
                        Export.Points -> temp.writeText(result.output.pointsJson)
                    }
                    ensureActive()
                    val stream = requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出文件" }
                    stream.use { output -> temp.inputStream().use { input -> copy(input, output) { ensureActive() } } }
                } finally {
                    temp.delete()
                }
            }
            message = "已导出${if (export == Export.Points) "踩音点" else "谱面"}"
        }
    }

    private fun buildPez(result: Completed, chart: AutoChartResult, output: File) {
        PezPackager().build(
            outFile = output,
            rpe = chart.rpe,
            meta = InfoFiles.PezMeta(
                name = result.meta.name.ifBlank { result.audio.name.substringBeforeLast('.') },
                level = chart.level,
                composer = result.meta.composer,
                charter = result.meta.charter,
                audioExt = result.audio.extension,
                coverExt = result.cover?.extension ?: ".png",
                audioLengthSec = result.output.analysis.durationSec,
            ),
            musicFile = result.audio.file,
            coverFile = result.cover?.file,
            pid = chart.chartId,
        )
    }

    private fun runTask(stage: String, canCancel: Boolean = false, action: suspend () -> Unit) {
        if (progress != null) return
        progress = Progress(null, stage, canCancel)
        error = null
        message = null
        job = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                message = "已取消"
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "$stage 失败"
            } finally {
                progress = null
            }
        }
    }

    private suspend fun copyMedia(uri: Uri, kind: InputKind): InputFile = coroutineScope {
        val name = displayName(uri, kind.label)
        val extension = extensionOf(name.substringAfterLast('.', ""))
            ?: resolver.getType(uri)?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)?.let(::extensionOf)
            ?: kind.fallbackExtension
        workDir.mkdirs()
        val target = File.createTempFile("import-", extension, workDir)
        try {
            val stream = requireNotNull(resolver.openInputStream(uri)) { "无法打开${kind.label}文件" }
            stream.use { input -> target.outputStream().use { output -> copy(input, output) { ensureActive() } } }
            require(target.length() > 0) { "${kind.label}文件为空" }
            InputFile(target, name, extension)
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    private fun displayName(uri: Uri, fallback: String): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: fallback

    private fun PickedFile.asInput(kind: InputKind) =
        InputFile(file, displayName, extensionOf(extension.removePrefix(".")) ?: kind.fallbackExtension)

    private fun extensionOf(value: String): String? = value.lowercase(Locale.ROOT)
        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        ?.let { ".$it" }

    private inline fun copy(input: InputStream, output: OutputStream, checkpoint: () -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkpoint()
            val length = input.read(buffer)
            if (length < 0) break
            output.write(buffer, 0, length)
        }
    }

    private fun removeUnusedImports() {
        val retained = setOf(selectedAudio?.file, selectedCover?.file, completed?.audio?.file, completed?.cover?.file)
        importedFiles.removeAll { file -> file !in retained && (file.delete() || !file.exists()) }
    }

    override fun onCleared() {
        importedFiles.forEach { it.delete() }
        super.onCleared()
    }

    private companion object {
        const val LONG_TRACK_CONFIRM_SECONDS = 480.0
        const val MAX_TRACK_SECONDS = AutoChartConfig.MAX_AUDIO_DURATION_SEC.toDouble()
        const val MAX_POINTS_BYTES = 16 * 1024 * 1024
    }
}
