package com.touchbridge.rpe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touchbridge.rpe.ui.theme.AppColors
import kotlin.math.roundToInt

@Composable
fun ScreenBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.Bg0, AppColors.Bg1, Color(0xFF0A1120)),
                ),
            ),
        content = content,
    )
}

@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    accent: Color = AppColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color(0x14243A5C)))),
                RoundedCornerShape(20.dp),
            ),
        color = Color(0xCC111C33),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ValueSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    unit: String = "",
    decimals: Int = 2,
    onChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            val shown = if (decimals <= 0) value.roundToInt().toString() else "%.${decimals}f".format(value)
            Text(
                "$shown$unit",
                color = AppColors.PrimaryBright,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AppColors.PrimaryBright,
                activeTrackColor = AppColors.Primary,
                inactiveTrackColor = Color(0xFF24324F),
            ),
        )
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
fun HSpacer(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun RowWrapSpacer() = Spacer(Modifier.size(8.dp))
