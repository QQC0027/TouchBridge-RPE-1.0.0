package com.touchbridge.rpe.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.touchbridge.rpe.domain.engine.BeatClock
import com.touchbridge.rpe.domain.model.RpeNote
import com.touchbridge.rpe.ui.theme.AppColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val VISIBLE_BEATS = 6.0

/**
 * 类 Phigros 的竖向滚动预览：判定线可置顶或置底，音符自远端下落。
 * 点击画布会回调点击位置对应的总拍数，由页面换算成音乐时间后 seek。
 */
@Composable
fun ChartCanvas(
    notes: List<RpeNote>,
    clock: BeatClock,
    musicSec: Double,
    judgeLineBottom: Boolean,
    onSeekBeat: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val musicState = rememberUpdatedState(musicSec)

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(judgeLineBottom, clock) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat()
                    val lineY = if (judgeLineBottom) h * 0.90f else h * 0.10f
                    val span = h * 0.80f
                    val t = if (judgeLineBottom) {
                        (lineY - offset.y) / span
                    } else {
                        (offset.y - lineY) / span
                    }
                    val beat = clock.timeToBeat(musicState.value) + t * VISIBLE_BEATS
                    onSeekBeat(beat.coerceAtLeast(0.0))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val topY = h * 0.10f
        val bottomY = h * 0.90f
        val span = bottomY - topY
        val lineY = if (judgeLineBottom) bottomY else topY
        val cur = clock.timeToBeat(musicSec)

        fun yOf(beat: Double): Float {
            val t = ((beat - cur) / VISIBLE_BEATS).toFloat()
            return if (judgeLineBottom) lineY - t * span else lineY + t * span
        }

        fun xOf(positionX: Double): Float {
            val x01 = ((positionX + 675.0) / 1350.0).coerceIn(0.0, 1.0)
            return (x01 * w).toFloat()
        }

        // 舞台底
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFF0A1020), Color(0xFF0E1730), Color(0xFF0A1020)),
            ),
            size = Size(w, h),
        )

        // 9 条 X 分区
        for (i in 0..9) {
            val x = w * i / 9f
            drawLine(
                color = AppColors.Primary.copy(alpha = if (i == 0 || i == 9) 0.30f else 0.14f),
                start = Offset(x, topY),
                end = Offset(x, bottomY),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 拍点线
        val first = floor(cur).toInt()
        val last = ceil(cur + VISIBLE_BEATS).toInt()
        for (b in first..last) {
            val y = yOf(b.toDouble())
            if (y < topY - 2f || y > bottomY + 2f) continue
            val major = ((b % clock.beatsPerBar) + clock.beatsPerBar) % clock.beatsPerBar == 0
            drawLine(
                color = Color.White.copy(alpha = if (major) 0.20f else 0.07f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
            )
        }

        // 判定线发光
        drawLine(
            color = AppColors.PrimaryBright.copy(alpha = 0.16f),
            start = Offset(0f, lineY),
            end = Offset(w, lineY),
            strokeWidth = 14.dp.toPx(),
        )
        drawLine(
            color = AppColors.PrimaryBright.copy(alpha = 0.85f),
            start = Offset(0f, lineY),
            end = Offset(w, lineY),
            strokeWidth = 2.dp.toPx(),
        )

        val visible = notes.filter {
            it.endBeat >= cur - 0.5 && it.startBeat <= cur + VISIBLE_BEATS + 0.5
        }

        // Drag 轨迹连线（同 gid 连续段）
        val dragGroups = visible.filter { it.type == 4 && it.gid != null }
            .groupBy { it.gid!! }
        for ((_, group) in dragGroups) {
            val pts = group.sortedBy { it.startBeat }
            for (i in 1 until pts.size) {
                val a = Offset(xOf(pts[i - 1].positionX), yOf(pts[i - 1].startBeat))
                val b = Offset(xOf(pts[i].positionX), yOf(pts[i].startBeat))
                drawLine(
                    color = AppColors.Yellow.copy(alpha = 0.45f),
                    start = a,
                    end = b,
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        val noteW = 92.dp.toPx()
        val noteH = 11.dp.toPx()

        for (n in visible) {
            val x = xOf(n.positionX)
            when (n.type) {
                1 -> {
                    val y = yOf(n.startBeat)
                    drawRoundRect(
                        color = AppColors.Primary,
                        topLeft = Offset(x - noteW / 2f, y - noteH / 2f),
                        size = Size(noteW, noteH),
                        cornerRadius = CornerRadius(noteH / 2f),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.45f),
                        topLeft = Offset(x - noteW / 2f + 2f, y - noteH / 2f + 2f),
                        size = Size(noteW - 4f, noteH / 3f),
                        cornerRadius = CornerRadius(noteH / 6f),
                    )
                }

                2 -> {
                    val yStart = yOf(n.startBeat)
                    val yEnd = yOf(n.endBeat)
                    val top = min(yStart, yEnd)
                    val bottom = max(yStart, yEnd)
                    drawRoundRect(
                        color = AppColors.Green.copy(alpha = 0.55f),
                        topLeft = Offset(x - 9.dp.toPx(), top),
                        size = Size(18.dp.toPx(), bottom - top),
                        cornerRadius = CornerRadius(9.dp.toPx()),
                    )
                    drawRoundRect(
                        color = AppColors.Green,
                        topLeft = Offset(x - noteW / 2f, yStart - noteH / 2f),
                        size = Size(noteW, noteH),
                        cornerRadius = CornerRadius(noteH / 2f),
                    )
                }

                3 -> {
                    val y = yOf(n.startBeat)
                    drawRoundRect(
                        color = AppColors.Red,
                        topLeft = Offset(x - noteW / 2f, y - noteH / 2f),
                        size = Size(noteW, noteH),
                        cornerRadius = CornerRadius(noteH / 2f),
                    )
                    val dirX = (n.dir?.first ?: 1.0).toFloat()
                    val arrow = Path().apply {
                        val tipX = x + dirX * (noteW / 2f + 7.dp.toPx())
                        val backX = x + dirX * (noteW / 2f - 1.dp.toPx())
                        moveTo(tipX, y)
                        lineTo(backX, y - 6.dp.toPx())
                        lineTo(backX, y + 6.dp.toPx())
                        close()
                    }
                    drawPath(arrow, AppColors.Red)
                }

                else -> {
                    val y = yOf(n.startBeat)
                    val s = 13.dp.toPx()
                    drawRoundRect(
                        color = AppColors.Yellow,
                        topLeft = Offset(x - s / 2f, y - s / 2f),
                        size = Size(s, s),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }

        // 判定线（前景，避免被音符盖住）
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(0f, lineY),
            end = Offset(w, lineY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
