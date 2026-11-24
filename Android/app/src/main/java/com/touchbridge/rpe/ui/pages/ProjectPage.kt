package com.touchbridge.rpe.ui.pages

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.GlowCard
import com.touchbridge.rpe.ui.components.ScreenBackground
import com.touchbridge.rpe.ui.components.SectionTitle
import com.touchbridge.rpe.ui.components.StatChip
import com.touchbridge.rpe.ui.theme.AppColors
import kotlinx.coroutines.delay

@Composable
fun ProjectPage(
    vm: AppViewModel,
    onRecord: () -> Unit,
    onPreview: () -> Unit,
    onAutoChart: () -> Unit,
) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickAudio)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickCover)
    }
    var showExport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshTick()
            delay(200)
        }
    }

    ScreenBackground {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 900.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "TOUCHBRIDGE / RPE",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = AppColors.PrimaryBright,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "移动制谱台",
                        style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "跟着音乐在屏幕上敲击、长按、划动，直接生成可在 Phira / PhiEdit 导入的 .pez",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                GlowCard(accent = AppColors.Primary) {
                    SectionTitle(
                        title = "音乐",
                        subtitle = vm.audioInfo?.displayName ?: "尚未选择音频",
                        trailing = {
                            TextButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                                Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("选择")
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    val duration = vm.audioDuration()
                    val max = if (duration > 0) duration else 1.0
                    Slider(
                        value = vm.musicTime.toFloat().coerceIn(0f, max.toFloat()),
                        onValueChange = { vm.seekToMusic(it.toDouble()) },
                        valueRange = 0f..max.toFloat(),
                        enabled = vm.audioReady,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatTime(vm.musicTime), color = AppColors.PrimaryBright, fontSize = 12.sp)
                        Text(formatTime(duration), color = AppColors.TextMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = vm::togglePlay,
                            enabled = vm.audioReady,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryDeep),
                        ) {
                            Icon(
                                if (vm.audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = AppColors.PrimaryBright,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (vm.audioPlaying) "暂停" else "播放", color = AppColors.PrimaryBright)
                        }
                        OutlinedButton(onClick = vm::stopPlayback, enabled = vm.audioReady) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("停止")
                        }
                        if (!vm.audioReady) {
                            Text(
                                "未导入音频也可录制（使用虚拟时钟）",
                                color = AppColors.TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                }
            }

            item {
                GlowCard(accent = AppColors.Violet) {
                    SectionTitle(
                        title = "曲绘",
                        subtitle = if (vm.coverInfo != null) "已导入，导出时写入 PEZ" else "可选，缺省使用渐变占位图",
                        trailing = {
                            Row {
                                TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                                    Text("选择")
                                }
                                if (vm.coverInfo != null) {
                                    TextButton(onClick = vm::clearCover) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Text("清除")
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    val cover = vm.coverInfo
                    if (cover != null) {
                        val bitmap = remember(cover.file.path, cover.file.length()) {
                            BitmapFactory.decodeFile(cover.file.path)?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "曲绘",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            CoverPlaceholder()
                        }
                    } else {
                        CoverPlaceholder()
                    }
                }
            }

            item {
                GlowCard(accent = AppColors.Green) {
                    SectionTitle("谱面信息", "会写入 RPE META 与 info.txt / info.yml")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = vm.meta.name,
                        onValueChange = { v -> vm.updateMeta { it.copy(name = v) } },
                        label = { Text("谱名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.meta.level,
                            onValueChange = { v -> vm.updateMeta { it.copy(level = v) } },
                            label = { Text("难度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = vm.meta.charter,
                            onValueChange = { v -> vm.updateMeta { it.copy(charter = v) } },
                            label = { Text("谱师") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.meta.composer,
                        onValueChange = { v -> vm.updateMeta { it.copy(composer = v) } },
                        label = { Text("作曲") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "BPM ${"%.1f".format(vm.settings.bpm)} · Offset ${"%.0f".format(vm.settings.offsetMs)} ms · 1/${vm.settings.subdivision} 细分",
                        color = AppColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            item {
                GlowCard(accent = AppColors.Yellow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val s = vm.stats
                        StatChip("Tap", (s?.tap ?: 0).toString(), AppColors.Primary, Modifier.weight(1f))
                        StatChip("Hold", (s?.hold ?: 0).toString(), AppColors.Green, Modifier.weight(1f))
                        StatChip("Flick", (s?.flick ?: 0).toString(), AppColors.Red, Modifier.weight(1f))
                        StatChip("Drag", (s?.drag ?: 0).toString(), AppColors.Yellow, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::loadDemo) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("注入示例手势")
                        }
                        TextButton(onClick = vm::clearNotes) {
                            Text("清空音符")
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onRecord,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始录制", fontWeight = FontWeight.Bold, color = Color(0xFF04121F))
                    }
                    OutlinedButton(
                        onClick = onAutoChart,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("自动制谱")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onPreview,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("预览谱面") }
                        OutlinedButton(
                            onClick = { showExport = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出 PEZ")
                        }
                    }
                }
            }

            item {
                Text(
                    "TouchBridge RPE 1.0.0 · 完全离线，不依赖电脑与 Python",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            }
        }
    }

    if (showExport) {
        ExportSheet(vm = vm, onDismiss = { showExport = false })
    }
}

@Composable
private fun CoverPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1E1E46), Color(0xFF3A2A5C)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.TextMuted)
            Spacer(Modifier.height(6.dp))
            Text("未选择曲绘 · 导出时生成渐变占位图", color = AppColors.TextMuted, fontSize = 12.sp)
        }
    }
}

fun formatTime(sec: Double): String {
    if (sec.isNaN() || sec <= 0) return "00:00.0"
    val m = (sec / 60).toInt()
    val s = sec - m * 60
    return "%02d:%04.1f".format(m, s)
}
