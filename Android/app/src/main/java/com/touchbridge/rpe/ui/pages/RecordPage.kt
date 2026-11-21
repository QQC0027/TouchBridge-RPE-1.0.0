package com.touchbridge.rpe.ui.pages

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.touch.TouchPadView
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.Dot
import com.touchbridge.rpe.ui.theme.AppColors
import kotlinx.coroutines.delay

@Composable
fun RecordPage(
    vm: AppViewModel,
    onExit: () -> Unit,
    onPreview: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var showSummary by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                previousBehavior ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshTick()
            delay(50)
        }
    }

    val clock = remember(vm.settings) { vm.clockFor() }
    val beat = clock.timeToBeat(vm.musicTime)
    val bar = (beat / vm.settings.beatsPerBar).toInt() + 1
    val beatInBar = ((beat % vm.settings.beatsPerBar) + vm.settings.beatsPerBar) % vm.settings.beatsPerBar + 1

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF05080F))) {
        AndroidView(
            factory = { ctx ->
                TouchPadView(ctx).apply {
                    onFrame = { samples, uptimeNanos -> vm.onTouchFrame(samples, uptimeNanos) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 网格 / 安全带 / 判定线（不拦截触摸）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val safe = 20.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x33000000), Color(0x00000000), Color(0x33000000)),
                ),
                size = size,
            )
            for (i in 0..9) {
                val x = w * i / 9f
                drawLine(
                    color = AppColors.Primary.copy(alpha = if (i == 0 || i == 9) 0.18f else 0.07f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val lineY = h * 0.52f
            drawLine(
                color = AppColors.PrimaryBright.copy(alpha = 0.10f),
                start = Offset(safe, lineY),
                end = Offset(w - safe, lineY),
                strokeWidth = 18.dp.toPx(),
            )
            drawLine(
                color = AppColors.PrimaryBright.copy(alpha = if (vm.recording) 0.55f else 0.22f),
                start = Offset(safe, lineY),
                end = Offset(w - safe, lineY),
                strokeWidth = 1.5.dp.toPx(),
            )
            // 安全带描边
            drawRect(
                color = AppColors.Primary.copy(alpha = 0.12f),
                topLeft = Offset(safe, safe),
                size = androidx.compose.ui.geometry.Size(w - safe * 2, h - safe * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }

        // 顶部 HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 40.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Color(0xCC0B1424),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Primary.copy(alpha = 0.3f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Dot(if (vm.recording) AppColors.Red else AppColors.TextMuted, 9)
                        Text(
                            if (vm.recording) "REC" else "STANDBY",
                            color = if (vm.recording) AppColors.Red else AppColors.TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                        Text(
                            "·  ${vm.noteCount} 音符",
                            color = AppColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    color = Color(0xCC0B1424),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Primary.copy(alpha = 0.3f)),
                ) {
                    Text(
                        "%02d:%04.1f".format((vm.musicTime / 60).toInt(), vm.musicTime % 60),
                        color = AppColors.PrimaryBright,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    if (vm.recording) vm.stopRecording()
                    onExit()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出录制", tint = AppColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudChip("拍号", "$bar 小节 / 第 ${"%02.1f".format(beatInBar)} 拍")
                HudChip("拍", "%.2f".format(beat))
                HudChip("手指", "${vm.fingerCount}")
                HudChip(
                    "手势",
                    vm.gestureHint,
                    accent = when (vm.gestureHint) {
                        "Tap" -> AppColors.Primary
                        "Hold" -> AppColors.Green
                        "Flick" -> AppColors.Red
                        "Drag" -> AppColors.Yellow
                        else -> AppColors.TextSecondary
                    },
                )
            }

            if (!vm.audioReady) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color(0xCC3A2A10),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "未导入音频：使用虚拟时钟，导出时包内无音乐（建议先在工程页导入）",
                        color = AppColors.Yellow,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 底部控制
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!vm.recording) {
                Text(
                    "在屏幕任意位置敲击 / 长按 / 划动",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
            }

            if (!vm.recording) {
                bigCircle(
                    color = AppColors.Primary,
                    icon = Icons.Rounded.FiberManualRecord,
                    onClick = {
                        showSummary = false
                        vm.startRecording()
                    },
                )
            } else {
                bigCircle(
                    color = AppColors.Red,
                    icon = Icons.Rounded.Stop,
                    onClick = {
                        vm.stopRecording()
                        showSummary = true
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (vm.recording) "正在录制 · 点击停止并生成谱面" else "点击开始录制",
                color = if (vm.recording) AppColors.Red else AppColors.TextSecondary,
                fontSize = 12.sp,
            )

            if (showSummary) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showSummary = false; vm.startRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryDeep),
                    ) {
                        Text("重录", color = AppColors.PrimaryBright)
                    }
                    Button(
                        onClick = {
                            showSummary = false
                            onPreview()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                    ) {
                        Text("查看预览", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 录制脉冲提示
        if (vm.recording) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.10f
                drawCircle(
                    color = AppColors.Red.copy(alpha = 0.06f),
                    radius = r,
                    center = Offset(size.width / 2f, size.height * 0.9f),
                )
            }
        }
    }
}

@Composable
private fun HudChip(label: String, value: String, accent: Color = AppColors.PrimaryBright) {
    Surface(
        color = Color(0xCC0B1424),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x332B3E5E)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = AppColors.TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun bigCircle(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(50)),
        )
        Box(
            modifier = Modifier
                .size(84.dp)
                .background(color.copy(alpha = 0.18f), RoundedCornerShape(50)),
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(68.dp)
                .background(color, RoundedCornerShape(50)),
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF04121F), modifier = Modifier.size(30.dp))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("unused")
private fun gestureColor(g: Gesture): Color = when (g) {
    Gesture.TAP -> AppColors.Primary
    Gesture.HOLD -> AppColors.Green
    Gesture.FLICK -> AppColors.Red
    Gesture.DRAG -> AppColors.Yellow
}
