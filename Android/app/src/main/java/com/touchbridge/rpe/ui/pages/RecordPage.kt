package com.touchbridge.rpe.ui.pages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.touchbridge.rpe.capture.BackgroundCaptureService
import com.touchbridge.rpe.capture.CaptureSupport
import com.touchbridge.rpe.capture.global.RootInputBackend
import com.touchbridge.rpe.capture.global.ShizukuSetup
import com.touchbridge.rpe.domain.model.Gesture
import com.touchbridge.rpe.touch.TouchPadView
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.Dot
import com.touchbridge.rpe.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecordPage(
    vm: AppViewModel,
    onExit: () -> Unit,
    onPreview: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var showSummary by remember { mutableStateOf(false) }
    var showSnap by remember { mutableStateOf(false) }
    var snapWindow by remember { mutableStateOf(120f) }
    var snapStrength by remember { mutableStateOf(1f) }
    if (showSnap) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSnap = false },
            title = { Text("吸附录制手势") },
            text = {
                Column {
                    Text("会根据音频检测结果调整 BPM 和偏移，可撤销。")
                    Text("搜索窗口：${snapWindow.toInt()} ms")
                    androidx.compose.material3.Slider(value = snapWindow, onValueChange = { snapWindow = it }, valueRange = 20f..250f)
                    Text("吸附强度：${(snapStrength * 100).toInt()}%")
                    androidx.compose.material3.Slider(value = snapStrength, onValueChange = { snapStrength = it })
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showSnap = false
                    vm.snapRecording(snapWindow.toDouble(), snapStrength.toDouble())
                }) { Text("开始吸附") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showSnap = false }) { Text("取消") } },
        )
    }

    var showBackground by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            vm.showToast("未授予通知权限，后台采集将缺少常驻状态提示", "warn")
        }
        startBackgroundCapture(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showBackground) {
        BackgroundCaptureDialog(
            context = context,
            refreshKey = permissionTick,
            onDismiss = { showBackground = false },
            onConfirm = {
                showBackground = false
                val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsRuntimePermission) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    if (!CaptureSupport.canPostNotifications(context)) {
                        vm.showToast("通知已关闭或通道被禁用，后台采集将缺少常驻状态提示", "warn")
                    }
                    startBackgroundCapture(context)
                }
            },
        )
    }

    vm.recoveryPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("发现未完成的录制") },
            text = {
                Column {
                    Text("会话 ${prompt.sessionId}", fontSize = 12.sp, color = AppColors.TextMuted)
                    Text("已恢复 ${prompt.noteCount} 个手势，时长约 ${prompt.durationSec.toInt()} 秒")
                    if (prompt.damaged) {
                        Text(
                            "日志尾部不完整，已保留成功写入的部分。",
                            color = AppColors.Yellow,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::recoverPendingSession) { Text("恢复") } },
            dismissButton = { TextButton(onClick = vm::discardPendingSession) { Text("丢弃") } },
        )
    }

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
                            when {
                                vm.backgroundRecording -> "BG REC"
                                vm.recording -> "REC"
                                else -> "STANDBY"
                            },
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
                val backgroundSupported = CaptureSupport.isPlatformSupported()
                Surface(
                    color = Color(0xCC0B1424),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (backgroundSupported) AppColors.Primary.copy(alpha = 0.35f) else Color(0x332B3E5E),
                    ),
                    modifier = Modifier.clickable {
                        when {
                            vm.recording -> vm.showToast("当前正在录制，请先停止", "warn")
                            !backgroundSupported -> vm.showToast("当前系统版本不支持后台采集", "warn")
                            else -> showBackground = true
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (backgroundSupported) AppColors.PrimaryBright else AppColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "后台采集",
                            color = if (backgroundSupported) AppColors.PrimaryBright else AppColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = {
                    if (vm.backgroundRecording) {
                        // 服务结束后会自动打开预览页，不能再 pop，否则会抢在跳转前退出。
                        vm.stopRecording()
                    } else {
                        if (vm.recording) vm.stopRecording()
                        onExit()
                    }
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

            vm.backgroundMessage?.let { message ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = if (vm.backgroundRecording) Color(0xCC3A1420) else Color(0xCC0B1424),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(if (vm.backgroundRecording) AppColors.Red else AppColors.Yellow, 7)
                        Spacer(Modifier.width(6.dp))
                        Text(message, color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
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

            if (!vm.recording && vm.noteCount > 0 && vm.audioInfo != null && com.touchbridge.rpe.domain.autochart.AutoChartFeature.ENABLED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = { showSnap = true }, enabled = !vm.snapInProgress) {
                        Text(if (vm.snapInProgress) "分析音频中…" else "吸附对齐")
                    }
                    if (vm.canUndoSnap) androidx.compose.material3.TextButton(onClick = vm::undoSnap) { Text("撤销吸附") }
                }
            }
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

@Composable
private fun BackgroundCaptureDialog(
    context: Context,
    refreshKey: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val accessibilitySupported = CaptureSupport.isAccessibilityCaptureSupported()
    val accessibility = remember(refreshKey) { CaptureSupport.isAccessibilityEnabled(context) }
    val overlay = remember(refreshKey) { CaptureSupport.canDrawOverlays(context) }
    val notification = remember(refreshKey) { CaptureSupport.canPostNotifications(context) }
    val shizukuGranted by ShizukuSetup.permission.collectAsState()
    var rootReady by remember(refreshKey) { mutableStateOf(RootInputBackend.cachedAvailable == true) }
    var probingRoot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val privilegedReady = shizukuGranted || rootReady
    val canStart = remember(refreshKey, shizukuGranted, rootReady, accessibility, overlay) {
        CaptureSupport.canStartBackgroundCapture(context) ||
            (overlay && !accessibilitySupported && privilegedReady)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台采集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "进入后应用会退到桌面，右上角保留小型悬浮控制条：开始录制 / 返回；录制中只保留结束录制。触摸会透传给当前 App 并由系统级通道采集。",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
                if (accessibilitySupported) {
                    PermissionRow("无障碍服务（Android 13+ 采集）", accessibility) {
                        CaptureSupport.openAccessibilitySettings(context)
                    }
                } else {
                    Text(
                        "Android 8–12 没有公开的全局触摸 API，必须通过 Shizuku 或 Root 读取触摸设备。",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                    PermissionRow("Shizuku 授权", shizukuGranted) {
                        if (!ShizukuSetup.requestPermission()) {
                            if (ShizukuSetup.openManager(context)) {
                                Toast.makeText(context, "请在 Shizuku 中启动服务后再回来授权", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "未检测到 Shizuku，请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    PermissionRow("Root（su 兜底）", rootReady) {
                        if (probingRoot) return@PermissionRow
                        probingRoot = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootInputBackend.probe() }
                            rootReady = ok
                            probingRoot = false
                            Toast.makeText(
                                context,
                                if (ok) "Root 可读取触摸设备" else "Root 探测失败：未授权或无法读取 /dev/input",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                PermissionRow("悬浮窗（控制条显示）", overlay) {
                    CaptureSupport.openOverlaySettings(context)
                }
                PermissionRow("通知（前台服务状态）", notification) {
                    CaptureSupport.openNotificationSettings(context)
                }
                Text(
                    if (accessibilitySupported && Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                        "Android 13 的无障碍采集需要触摸探索模式，单指操作可能被识别为探索手势；Android 14+ 不再需要。"
                    } else if (accessibilitySupported) {
                        "Android 14+ 使用无障碍原始触摸事件，不需要触摸探索模式。"
                    } else {
                        "Shizuku 以 shell 身份运行时，部分系统会拒绝读取 /dev/input；此时请改用 Root。"
                    },
                    fontSize = 11.sp,
                    color = AppColors.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canStart) {
                Text(if (canStart) "进入后台采集" else "请先完成采集通道配置")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (granted) AppColors.Green else AppColors.Yellow, 8)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (granted) {
            Text("已开启", fontSize = 12.sp, color = AppColors.Green)
        } else {
            TextButton(onClick = onOpen) { Text("去开启") }
        }
    }
}

private fun startBackgroundCapture(context: Context) {
    if (!BackgroundCaptureService.start(context)) return
    val activity = context.findActivity()
    if (activity != null) {
        activity.moveTaskToBack(true)
    } else {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
