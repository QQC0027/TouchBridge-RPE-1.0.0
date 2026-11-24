package com.touchbridge.rpe.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.touchbridge.rpe.ui.AppViewModel
import com.touchbridge.rpe.ui.components.StatChip
import com.touchbridge.rpe.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var pendingShare by remember { mutableStateOf(false) }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.showToast("未授予存储权限，谱面将保存到应用目录", "warn")
        vm.exportPez(share = pendingShare)
    }
    val startExport: (Boolean) -> Unit = { share ->
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingShare = share
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.exportPez(share = share)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Bg1,
        contentColor = AppColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "EXPORT .PEZ",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = AppColors.PrimaryBright,
            )
            Text(
                "导出谱面",
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
            )
            Text(
                "包内平铺谱面 JSON、音乐、曲绘与 info.txt / info.yml，可直接导入 Phira 或 PhiEdit。",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val s = vm.stats
                StatChip("Tap", (s?.tap ?: 0).toString(), AppColors.Primary, Modifier.weight(1f))
                StatChip("Hold", (s?.hold ?: 0).toString(), AppColors.Green, Modifier.weight(1f))
                StatChip("Flick", (s?.flick ?: 0).toString(), AppColors.Red, Modifier.weight(1f))
                StatChip("Drag", (s?.drag ?: 0).toString(), AppColors.Yellow, Modifier.weight(1f))
            }

            OutlinedTextField(
                value = vm.meta.name,
                onValueChange = { v -> vm.updateMeta { it.copy(name = v) } },
                label = { Text("谱名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
            OutlinedTextField(
                value = vm.meta.composer,
                onValueChange = { v -> vm.updateMeta { it.copy(composer = v) } },
                label = { Text("作曲") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!vm.audioReady) {
                Text(
                    "尚未导入音频：导出的包内不会有音乐文件，Phira 需要手动补齐。",
                    color = AppColors.Yellow,
                    fontSize = 12.sp,
                )
            }
            if (vm.previewNotes.isEmpty()) {
                Text(
                    "当前音符为空，导出的谱面将没有音符。请先录制或注入示例手势。",
                    color = AppColors.Red,
                    fontSize = 12.sp,
                )
            }

            Button(
                onClick = { startExport(false) },
                enabled = !vm.exporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (vm.exporting) "正在打包…" else "导出并保存到下载目录",
                    color = Color(0xFF04121F),
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = { startExport(true) },
                enabled = !vm.exporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导出并分享到 Phira / 编辑器")
            }

            if (vm.exporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            vm.lastExport?.let { outcome ->
                Column {
                    Text("最近一次导出", color = AppColors.TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(outcome.fileName, color = AppColors.PrimaryBright, fontSize = 13.sp)
                    Text(outcome.path, color = AppColors.TextSecondary, fontSize = 11.sp)
                }
            }

            Text(
                "提示：导出后若发现整体偏前或偏后，回到参数页点一次「自动校准」，再重新导出。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}
