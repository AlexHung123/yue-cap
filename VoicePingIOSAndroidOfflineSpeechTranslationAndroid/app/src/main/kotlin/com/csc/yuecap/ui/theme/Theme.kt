package com.csc.yuecap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object YueCapColors {
    val Ink = Color(0xFF071018)
    val InkRaised = Color(0xFF101A24)
    val Panel = Color(0xFF162433)
    val Line = Color(0xFF2A3C4E)
    val Gold = Color(0xFFE4C36A)
    val Paper = Color(0xFFF6F3EC)
    val Mist = Color(0xFF93A4B5)
    val Danger = Color(0xFFE15D55)
    val Ok = Color(0xFF3DDC97)
    /** Dark floating-caption card background (reference overlay look). */
    val CaptionCard = Color(0xE6111822)
    /** Already-confirmed / already-read caption text (dim). */
    val CaptionPast = Color(0xFF8B97A5)
    /** Live hypothesis / currently speaking caption text (bright). */
    val CaptionCurrent = Color(0xFFF7F4EE)
}

private val YueCapScheme = darkColorScheme(
    primary = YueCapColors.Gold,
    onPrimary = YueCapColors.Ink,
    secondary = YueCapColors.Mist,
    onSecondary = YueCapColors.Ink,
    background = YueCapColors.Ink,
    onBackground = YueCapColors.Paper,
    surface = YueCapColors.InkRaised,
    onSurface = YueCapColors.Paper,
    surfaceVariant = YueCapColors.Panel,
    onSurfaceVariant = YueCapColors.Mist,
    outline = YueCapColors.Line,
    error = YueCapColors.Danger,
    onError = YueCapColors.Paper
)

private val YueCapType = Typography(
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
)

@Composable
fun OfflineTranscriptionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YueCapScheme,
        typography = YueCapType,
        content = content
    )
}
