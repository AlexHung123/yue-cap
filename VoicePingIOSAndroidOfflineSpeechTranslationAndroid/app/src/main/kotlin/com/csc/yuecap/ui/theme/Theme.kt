package com.csc.yuecap.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark glassmorphism tokens — charcoal surfaces, frosted translucent panels,
 * white/mist labels, optional cool-blue edge glow. Karaoke caption colors stay
 * dim-past / bright-current.
 */
object YueCapColors {
    val Ink = Color(0xFF0A0B0E)
    val InkRaised = Color(0xFF12141A)
    val Panel = Color(0xFF1A1D24)
    /** Semi-transparent frosted glass fill for cards. */
    val Glass = Color(0x99171B22)
    /** Stronger glass for primary caption / sheets. */
    val GlassStrong = Color(0xD912151C)
    /** Soft white edge for glass borders. */
    val Line = Color(0x33FFFFFF)
    /** Cool blue edge glow (use sparingly). */
    val Glow = Color(0x663A6EA8)
    val Gold = Color(0xFFE4C36A)
    val Paper = Color(0xFFF2F4F7)
    val Mist = Color(0xFF9AA3B2)
    val Danger = Color(0xFFE15D55)
    val Ok = Color(0xFF3DDC97)
    /** Dark floating-caption card background (frosted). */
    val CaptionCard = Color(0xE611141B)
    /** Already-confirmed / already-read caption text (dim). */
    val CaptionPast = Color(0xFF8B97A5)
    /** Live hypothesis / currently speaking caption text (bright). */
    val CaptionCurrent = Color(0xFFF7F4EE)
    /** Circular translucent chrome fill (settings-style). */
    val IconChrome = Color(0x33FFFFFF)
}

private val YueCapScheme = darkColorScheme(
    primary = YueCapColors.Paper,
    onPrimary = YueCapColors.Ink,
    secondary = YueCapColors.Mist,
    onSecondary = YueCapColors.Ink,
    tertiary = YueCapColors.Gold,
    onTertiary = YueCapColors.Ink,
    background = YueCapColors.Ink,
    onBackground = YueCapColors.Paper,
    surface = YueCapColors.InkRaised,
    onSurface = YueCapColors.Paper,
    surfaceVariant = YueCapColors.Panel,
    onSurfaceVariant = YueCapColors.Mist,
    outline = YueCapColors.Line,
    error = YueCapColors.Danger,
    onError = YueCapColors.Paper,
    secondaryContainer = YueCapColors.GlassStrong,
    onSecondaryContainer = YueCapColors.Paper,
    primaryContainer = YueCapColors.Panel,
    onPrimaryContainer = YueCapColors.Paper
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

/** Soft rounded glass panel — translucent fill + subtle border (+ optional cool glow). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    glow: Boolean = false,
    strong: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(corner)
    val fill = if (strong) YueCapColors.GlassStrong else YueCapColors.Glass
    val borderBrush = if (glow) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f),
                YueCapColors.Glow,
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    }
    Box(
        modifier = modifier
            .then(
                if (glow) Modifier.shadow(12.dp, shape, ambientColor = YueCapColors.Glow, spotColor = YueCapColors.Glow)
                else Modifier
            )
            .clip(shape)
            .background(fill, shape)
            .border(BorderStroke(1.dp, borderBrush), shape)
    ) {
        content()
    }
}

/** Circular translucent chrome control (reference settings / close wells). */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = YueCapColors.Paper
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = YueCapColors.IconChrome,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
