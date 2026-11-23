package com.touchbridge.rpe.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.Dot
import com.touchbridge.rpe.ui.components.GlowCard
import com.touchbridge.rpe.ui.components.ScreenBackground
import com.touchbridge.rpe.ui.components.StatChip
import com.touchbridge.rpe.ui.preview.ChartCanvas
import com.touchbridge.rpe.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun PreviewPage(vm: AppViewModel, onSettings: () -> Unit) {
    var judgeBottom by remember { mutableStateOf(false) }
    var music by remember { mutableStateOf(0.0) }
    var virtualBaseMs by remember { mutableStateOf(android.os.SystemClock.uptimeMillis()) }
    var virtualBaseSec by remember { mutableStateOf(0.0) }
    val clock = remember(vm.settings) { vm.clockFor() }

    LaunchedEffect(Unit) {
        vm.rebuildPreview()
        while (true) {
            music = if (vm.audioReady) {
                vm.audioPosition()
            } else {
                virtualBaseSec + (android.os.SystemClock.uptimeMillis() - virtualBaseMs) / 1000.0
            }
            delay(33)
        }
    }

    val lastBeat = vm.previewNotes.maxOfOrNull { it.endBeat } ?: 0.0
    val duration = max(vm.audioDuration(), clock.beatToTime(lastBeat + 4.0)).coerceAtLeast(4.0)
    val beat = clock.timeToBeat(music)

    ScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CHART PREVIEW",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = AppColors.PrimaryBright,
                    )
                    Text("预览", style = androidx.compose.material3.MaterialTheme.typography.displaySmall)
                }
                IconButton(onClick = { vm.rebuildPreview() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新生成", tint = AppColors.TextSecondary)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Tune, contentDescription = "参数", tint = AppColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val s = vm.stats
                StatChip("Tap", (s?.tap ?: 0).toString(), AppColors.Primary, Modifier.weight(1f))
                StatChip("Hold", (s?.hold ?: 0).toString(), AppColors.Green, Modifier.weight(1f))
                StatChip("Flick", (s?.flick ?: 0).toString(), AppColors.Red, Modifier.weight(1f))
                StatChip("Drag", (s?.drag ?: 0).toString(), AppColors.Yellow, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)),
            ) {
                ChartCanvas(
                    notes = vm.previewNotes,
                    clock = clock,
                    musicSec = music,
                    judgeLineBottom = judgeBottom,
                    onSeekBeat = { b ->
                        val t = clock.beatToTime(b)
                        vm.seekToMusic(t)
                        music = t
                        if (!vm.audioReady) {
                            virtualBaseSec = t
                            virtualBaseMs = android.os.SystemClock.uptimeMillis()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Surface(
                    modifier = Modifier.padding(10.dp),
                    color = Color(0x99070B14),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppColors.Primary.copy(alpha = 0.25f)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            "BEAT %.2f".format(beat),
                            color = AppColors.PrimaryBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "%.2f s".format(music),
                            color = AppColors.TextMuted,
                            fontSize = 10.sp,
                        )
                    }
                }

                if (vm.previewNotes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("当前没有音符", color = AppColors.TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "先录制，或注入示例手势快速查看效果",
                            color = AppColors.TextMuted,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { vm.loadDemo() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryDeep),
                        ) {
                            Text("注入示例手势", color = AppColors.PrimaryBright)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            GlowCard(accent = AppColors.Primary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = vm::togglePlay,
                        enabled = vm.audioReady,
                        modifier = Modifier.height(42.dp),
                    ) {
                        Icon(
                            if (vm.audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.audioPlaying) "暂停" else "播放")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = vm::stopPlayback,
                        enabled = vm.audioReady,
                        modifier = Modifier.height(42.dp),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatTime(music),
                        color = AppColors.PrimaryBright,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = judgeBottom,
                        onClick = { judgeBottom = !judgeBottom },
                        label = { Text(if (judgeBottom) "判定线：底" else "判定线：顶") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryDeep,
                            selectedLabelColor = AppColors.PrimaryBright,
                        ),
                    )
                }
                Slider(
                    value = music.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = {
                        vm.seekToMusic(it.toDouble())
                        music = it.toDouble()
                        if (!vm.audioReady) {
                            virtualBaseSec = it.toDouble()
                            virtualBaseMs = android.os.SystemClock.uptimeMillis()
                        }
                    },
                    valueRange = 0f..duration.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot(AppColors.Primary, "Tap")
                    LegendDot(AppColors.Green, "Hold")
                    LegendDot(AppColors.Red, "Flick")
                    LegendDot(AppColors.Yellow, "Drag")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSettings) { Text("手势参数") }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 8)
        Spacer(Modifier.width(5.dp))
        Text(text, color = AppColors.TextSecondary, fontSize = 11.sp)
    }
}
