package com.touchbridge.rpe.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.GlowCard
import com.touchbridge.rpe.ui.components.ScreenBackground
import com.touchbridge.rpe.ui.components.SectionTitle
import com.touchbridge.rpe.ui.components.ValueSlider
import com.touchbridge.rpe.ui.theme.AppColors
import kotlin.math.roundToInt

@Composable
fun SettingsPage(vm: AppViewModel) {
    val s = vm.settings
    ScreenBackground {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 900.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item {
                Column {
                    Text(
                        "CHART PARAMETERS",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = AppColors.PrimaryBright,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("参数", style = androidx.compose.material3.MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "与 PC 版同名同默认值，改动会立即保存并作用于下一次生成",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                GlowCard(accent = AppColors.Primary) {
                    SectionTitle("节拍", "BeatClock：多段变速预留 segments")
                    ValueSlider(
                        label = "BPM",
                        value = s.bpm,
                        min = 40.0,
                        max = 300.0,
                        unit = "",
                        decimals = 1,
                    ) { v -> vm.updateSettings { it.copy(bpm = v) } }
                    ValueSlider(
                        label = "Offset",
                        value = s.offsetMs,
                        min = -500.0,
                        max = 500.0,
                        unit = " ms",
                        decimals = 0,
                    ) { v -> vm.updateSettings { it.copy(offsetMs = v) } }
                    ValueSlider(
                        label = "每小节拍数",
                        value = s.beatsPerBar.toDouble(),
                        min = 1.0,
                        max = 12.0,
                        decimals = 0,
                    ) { v -> vm.updateSettings { it.copy(beatsPerBar = v.roundToInt().coerceIn(1, 12)) } }
                    Spacer(Modifier.height(8.dp))
                    Text("细分", color = AppColors.TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 8, 16).forEach { sub ->
                            FilterChip(
                                selected = s.subdivision == sub,
                                onClick = { vm.updateSettings { it.copy(subdivision = sub) } },
                                label = { Text("1/$sub") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppColors.PrimaryDeep,
                                    selectedLabelColor = AppColors.PrimaryBright,
                                ),
                            )
                        }
                    }
                }
            }

            item {
                GlowCard(accent = AppColors.Violet) {
                    SectionTitle("量化与映射", "吸附过松会让自由拍丢失，过紧则手感发硬")
                    ValueSlider(
                        label = "吸附容差",
                        value = s.snapTolerance,
                        min = 0.0,
                        max = 1.0,
                        decimals = 2,
                    ) { v -> vm.updateSettings { it.copy(snapTolerance = v) } }
                    ValueSlider(
                        label = "去抖",
                        value = s.debounceMs,
                        min = 0.0,
                        max = 200.0,
                        unit = " ms",
                        decimals = 0,
                    ) { v -> vm.updateSettings { it.copy(debounceMs = v) } }
                    Spacer(Modifier.height(6.dp))
                    Text("X 映射方式", color = AppColors.TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = s.xMode == "coordinate",
                            onClick = { vm.updateSettings { it.copy(xMode = "coordinate") } },
                            label = { Text("连续坐标") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.PrimaryDeep,
                                selectedLabelColor = AppColors.PrimaryBright,
                            ),
                        )
                        FilterChip(
                            selected = s.xMode == "region",
                            onClick = { vm.updateSettings { it.copy(xMode = "region") } },
                            label = { Text("区域吸附") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.PrimaryDeep,
                                selectedLabelColor = AppColors.PrimaryBright,
                            ),
                        )
                    }
                    ValueSlider(
                        label = "区域数",
                        value = s.regions.toDouble(),
                        min = 3.0,
                        max = 16.0,
                        decimals = 0,
                    ) { v -> vm.updateSettings { it.copy(regions = v.roundToInt().coerceIn(3, 16)) } }
                }
            }

            item {
                GlowCard(accent = AppColors.Green) {
                    SectionTitle("手势判定", "距离类阈值按屏幕短边归一化，手机与平板分别标定")
                    ValueSlider("死区", s.deadZone, 0.005, 0.15, decimals = 3) { v ->
                        vm.updateSettings { it.copy(deadZone = v) }
                    }
                    ValueSlider("Hold 最短时长", s.holdMinTime, 0.05, 0.6, unit = " s") { v ->
                        vm.updateSettings { it.copy(holdMinTime = v) }
                    }
                    ValueSlider("Flick 时间窗", s.flickWindow, 0.05, 0.5, unit = " s") { v ->
                        vm.updateSettings { it.copy(flickWindow = v) }
                    }
                    ValueSlider("Flick 速度阈值", s.flickSpeed, 0.5, 6.0, decimals = 2) { v ->
                        vm.updateSettings { it.copy(flickSpeed = v) }
                    }
                    ValueSlider("Flick 最大距离", s.flickMaxDist, 0.05, 0.8, decimals = 3) { v ->
                        vm.updateSettings { it.copy(flickMaxDist = v) }
                    }
                }
            }

            item {
                GlowCard(accent = AppColors.Yellow) {
                    SectionTitle("拖拽", "轨迹先抽稀、再做三点平滑，最后按采样间隔下采样")
                    ValueSlider("采样间隔", s.dragSampleMs, 5.0, 80.0, unit = " ms", decimals = 0) { v ->
                        vm.updateSettings { it.copy(dragSampleMs = v) }
                    }
                    ValueSlider("最小速度", s.dragMinSpeed, 0.05, 1.0, decimals = 2) { v ->
                        vm.updateSettings { it.copy(dragMinSpeed = v) }
                    }
                    ValueSlider("最长时长", s.dragMaxDur, 0.3, 3.0, unit = " s") { v ->
                        vm.updateSettings { it.copy(dragMaxDur = v) }
                    }
                }
            }

            item {
                GlowCard(accent = AppColors.Red) {
                    SectionTitle("校准", "用已录音符相对整拍的偏差中位数修正音画延迟")
                    ValueSlider("采样补偿", s.hitOffsetMs, -300.0, 300.0, unit = " ms", decimals = 0) { v ->
                        vm.updateSettings { it.copy(hitOffsetMs = v) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = vm::autoCalibrate,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryDeep),
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = AppColors.PrimaryBright)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "自动校准采样补偿",
                            color = AppColors.PrimaryBright,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正数表示把音符整体延后，负数表示提前。导出前建议对同一段音乐至少校准一次。",
                        color = AppColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            item {
                Text(
                    "PC 版对应 backend/config.json 的 settings 段；segments 为空时使用单一 BPM。",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            }
        }
    }
}
