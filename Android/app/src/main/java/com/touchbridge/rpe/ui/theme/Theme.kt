package com.touchbridge.rpe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AppColors {
    val Primary = Color(0xFF3AA0FF)
    val PrimaryBright = Color(0xFF6BD6FA)
    val PrimaryDeep = Color(0xFF1B2C4D)
    val Bg0 = Color(0xFF070B14)
    val Bg1 = Color(0xFF0E1730)
    val Bg2 = Color(0xFF111C33)
    val TextPrimary = Color(0xFFF2F6FF)
    val TextSecondary = Color(0xFF9FB0CC)
    val TextMuted = Color(0xFF5E7192)
    val Green = Color(0xFF33D69F)
    val Yellow = Color(0xFFFFCF4D)
    val Red = Color(0xFFFF5D6C)
    val Violet = Color(0xFF7C6CFF)
}

private val DarkScheme = darkColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color(0xFF04121F),
    primaryContainer = AppColors.PrimaryDeep,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.PrimaryBright,
    onSecondary = Color(0xFF04121F),
    tertiary = AppColors.Green,
    background = AppColors.Bg0,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Bg1,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Bg2,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = Color(0xFF24324F),
    error = AppColors.Red,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 0.5.sp,
        color = AppColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        color = AppColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = AppColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = AppColors.TextSecondary,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        color = AppColors.TextMuted,
    ),
)

@Composable
fun TouchBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
