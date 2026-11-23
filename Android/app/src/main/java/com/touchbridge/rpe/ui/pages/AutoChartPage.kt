package com.touchbridge.rpe.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touchbridge.rpe.domain.autochart.AutoChartConfig
import com.touchbridge.rpe.domain.autochart.AutoChartOptions
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.AutoChartViewModel
import com.touchbridge.rpe.ui.AutoChartViewModel.Export
import com.touchbridge.rpe.ui.AutoChartViewModel.InputKind
import com.touchbridge.rpe.ui.components.GlowCard
import com.touchbridge.rpe.ui.components.ScreenBackground
import com.touchbridge.rpe.ui.components.SectionTitle
import com.touchbridge.rpe.ui.theme.AppColors

@Composable
fun AutoChartPage(appVm: AppViewModel, onBack: () -> Unit, vm: AutoChartViewModel = viewModel()) {
    var tier by rememberSaveable { mutableStateOf("hard") }
    var allTiers by rememberSaveable { mutableStateOf(false) }
    var keyMode by rememberSaveable { mutableStateOf("auto") }
    var density by rememberSaveable { mutableStateOf(1f) }
    var lineFx by rememberSaveable { mutableStateOf("none") }
    var pulse by rememberSaveable { mutableStateOf("chain") }
    var deco by rememberSaveable { mutableStateOf("intro_chorus") }
    var mainY by rememberSaveable { mutableStateOf("-300") }
    var mix by rememberSaveable { mutableStateOf("") }
    var swing by rememberSaveable { mutableStateOf(false) }
    var seed by rememberSaveable { mutableStateOf("42") }
    var tap by rememberSaveable { mutableStateOf("") }
    var hold by rememberSaveable { mutableStateOf("") }
    var flick by rememberSaveable { mutableStateOf("") }
    var drag by rememberSaveable { mutableStateOf("") }
    var disableDrag by rememberSaveable { mutableStateOf(false) }
    var sweepOnly by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.pickMedia(uri, InputKind.Audio) }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.pickMedia(uri, InputKind.Cover) }
    }
    val pointsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickPoints) }
    val pezPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        vm.finishExport(it)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        vm.finishExport(it)
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        vm.finishExport(it)
    }
    val enabled = vm.progress == null && vm.longTrackSeconds == null
    val audioName = vm.selectedAudio?.name ?: appVm.audioInfo?.displayName
    val coverName = vm.selectedCover?.name ?: appVm.coverInfo?.displayName?.takeIf { vm.useProjectCover }
    val parsedY = mainY.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    val parsedSeed = seed.trim().toLongOrNull()
    val validWeights = listOf(tap, hold, flick, drag).all { it.isBlank() || parseWeight(it) != null }

    vm.longTrackSeconds?.let { duration ->
        AlertDialog(
            onDismissRequest = { vm.answerLongTrack(false) },
            title = { Text("长音频确认") },
            text = { Text("这段音频长约 ${"%.1f".format(duration / 60)} 分钟，分析可能需要较长时间。") },
            confirmButton = { TextButton(onClick = { vm.answerLongTrack(true) }) { Text("继续制谱") } },
            dismissButton = { TextButton(onClick = { vm.answerLongTrack(false) }) { Text("取消") } },
        )
    }

    ScreenBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自动制谱", style = MaterialTheme.typography.headlineMedium)
                            Text("在设备上分析音频并生成 RPE 谱面", color = AppColors.TextMuted)
                        }
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                }
                item {
                    GlowCard(accent = AppColors.Primary) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle("输入文件")
                            Text("音频 · ${audioName ?: "未选择"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = enabled) { Text("选择音频") }
                                if (vm.selectedAudio != null) {
                                    TextButton(onClick = vm::useCurrentAudio, enabled = enabled) { Text("使用工程音频") }
                                }
                            }
                            Text("曲绘 · ${coverName ?: "默认占位图"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }, enabled = enabled) { Text("选择曲绘") }
                                if (vm.selectedCover != null || (!vm.useProjectCover && appVm.coverInfo != null)) {
                                    TextButton(onClick = { vm.setCoverSource(true) }, enabled = enabled) { Text("使用工程曲绘") }
                                } else if (coverName != null) {
                                    TextButton(onClick = { vm.setCoverSource(false) }, enabled = enabled) { Text("使用占位图") }
                                }
                            }
                            if (vm.selectedCover != null) {
                                TextButton(onClick = { vm.setCoverSource(false) }, enabled = enabled) { Text("使用占位图") }
                            }
                            Text("踩音点 · ${vm.pointsName ?: "自动检测"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pointsPicker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("导入 points JSON") }
                                if (vm.pointsName != null) {
                                    TextButton(onClick = vm::clearPoints, enabled = enabled) { Text("清除") }
                                }
                            }
                        }
                    }
                }
                item {
                    GlowCard(accent = AppColors.Violet) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("谱面参数")
                            ToggleRow("一次生成五档难度", allTiers, enabled) { allTiers = it }
                            ChoiceRow("难度", difficulties, tier, enabled && !allTiers) { tier = it }
                            ChoiceRow("键型", keyModes, keyMode, enabled) { keyMode = it }
                            Text("写谱密度 ×${"%.2f".format(density)}", color = AppColors.TextMuted)
                            Slider(value = density, onValueChange = { density = it }, valueRange = .7f..1.6f, steps = 17, enabled = enabled)
                            ChoiceRow("判定线动效", effects, lineFx, enabled) { lineFx = it }
                            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级参数" else "展开高级参数") }
                            if (advanced) {
                                ChoiceRow("脉冲形态", pulseStyles, pulse, enabled) { pulse = it }
                                ChoiceRow("装饰段落", decoSections, deco, enabled) { deco = it }
                                NumberField("主判定线 Y", mainY, enabled, parsedY != null) { mainY = it }
                                OutlinedTextField(
                                    value = mix,
                                    onValueChange = { mix = it },
                                    label = { Text("组合特效") },
                                    placeholder = { Text("grid@all;sweep@chorus") },
                                    singleLine = true,
                                    enabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ToggleRow("主判定线左右摆动", swing, enabled) { swing = it }
                                ToggleRow("横扫仅作用于 Flick", sweepOnly, enabled) { sweepOnly = it }
                                NumberField("随机种子", seed, enabled, parsedSeed != null, KeyboardType.Number) { seed = it }
                                Text("音符权重（留空使用难度预设）", color = AppColors.TextMuted)
                                NumberField("Tap", tap, enabled, tap.isBlank() || parseWeight(tap) != null) { tap = it }
                                NumberField("Hold", hold, enabled, hold.isBlank() || parseWeight(hold) != null) { hold = it }
                                NumberField("Flick", flick, enabled, flick.isBlank() || parseWeight(flick) != null) { flick = it }
                                NumberField("Drag", drag, enabled, drag.isBlank() || parseWeight(drag) != null) { drag = it }
                                ToggleRow("禁用 Drag", disableDrag, enabled) { disableDrag = it }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            val options = AutoChartOptions(
                                tier = tier,
                                allTiers = allTiers,
                                keyMode = keyMode,
                                density = density.toDouble(),
                                lineFx = lineFx,
                                pulseStyle = pulse,
                                decoWhen = deco,
                                mainY = requireNotNull(parsedY),
                                mixSpec = mix.trim(),
                                mainSwing = swing,
                                tapWeight = parseWeight(tap),
                                holdWeight = parseWeight(hold),
                                flickWeight = parseWeight(flick),
                                dragWeight = parseWeight(drag),
                                seed = requireNotNull(parsedSeed),
                                disableDrag = disableDrag,
                                sweepFlickOnly = sweepOnly,
                            )
                            vm.requestGeneration(appVm.audioInfo, appVm.coverInfo, appVm.meta, options)
                        },
                        enabled = enabled && audioName != null && parsedY != null && parsedSeed != null && validWeights,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始制谱") }
                    if (parsedY == null || parsedSeed == null || !validWeights) {
                        Text("请检查高级参数中的数值", color = AppColors.Red)
                    }
                    vm.progress?.let { state ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.stage)
                            val percent = state.percent
                            if (percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            if (state.canCancel) TextButton(onClick = vm::cancel) { Text("取消制谱") }
                        }
                    }
                    vm.error?.let { Text(it, color = AppColors.Red) }
                    vm.message?.let { Text(it, color = AppColors.Green) }
                }
                val result = vm.completed
                if (result != null) {
                    item {
                        GlowCard(accent = AppColors.Green) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val analysis = result.output.analysis
                                SectionTitle("生成结果", "${"%.1f".format(analysis.bpm)} BPM · ${analysis.points.size} 个踩音点")
                                for (chart in result.output.charts) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("${chart.level} · ${chart.noteCount} 音符", modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = { vm.prepareExport(Export.Tier(chart.tier))?.let { pezPicker.launch(it) } },
                                            enabled = enabled,
                                        ) { Text("导出 PEZ") }
                                    }
                                }
                                if (result.output.charts.size > 1) {
                                    OutlinedButton(
                                        onClick = { vm.prepareExport(Export.AllTiers)?.let(zipPicker::launch) },
                                        enabled = enabled,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("导出五档汇总 ZIP") }
                                }
                                OutlinedButton(
                                    onClick = { vm.prepareExport(Export.Points)?.let(jsonPicker::launch) },
                                    enabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("导出 points JSON") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, values: Map<String, String>, selected: String, enabled: Boolean, onChanged: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppColors.TextMuted, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(values[selected] ?: selected)
            }
            DropdownMenu(expanded = enabled && expanded, onDismissRequest = { expanded = false }) {
                values.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChanged(value) })
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    valid: Boolean,
    keyboard: KeyboardType = KeyboardType.Decimal,
    onChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChanged,
        label = { Text(label) },
        enabled = enabled,
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parseWeight(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

private val difficulties = AutoChartConfig.presets.mapValues { (_, preset) -> "${preset.label} ${preset.level}" }
private val keyModes = mapOf("auto" to "自动", "2k" to "2K", "4k" to "4K", "6k" to "6K")
private val effects = mapOf(
    "none" to "无", "alpha_pulse" to "脉冲呼吸", "pinwheel" to "风车旋转", "grid" to "网格",
    "curve" to "曲线轨迹", "color_flow" to "变色流", "glow" to "光晕呼吸", "bounce" to "弹跳",
    "breathe" to "呼吸缩放", "meteor" to "流星", "echo_trail" to "回音残影", "spotlight" to "聚光", "mix" to "组合",
)
private val pulseStyles = mapOf("chain" to "链式", "sweep" to "横扫")
private val decoSections = mapOf("intro_chorus" to "前奏和副歌", "intro" to "前奏", "chorus" to "副歌", "verse" to "主歌", "repeat" to "重复段落", "all" to "全曲")
