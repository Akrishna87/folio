package io.github.akrishna87.mybooks.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Near-black with a warm amber accent and a teal second colour, like a reading lamp. */
object Palette {
    val Background = Color(0xFF0B0B0F)
    val Elevated = Color(0xFF16161C)
    val Elevated2 = Color(0xFF212128)
    val Highlight = Color(0xFF2C2C35)
    val Text = Color(0xFFFFFFFF)
    val SubText = Color(0xFFA6A4B0)
    val Faint = Color(0xFF6F6E7A)
    val Amber = Color(0xFFFFB547)
    val Teal = Color(0xFF35C2A6)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Color(0xFF241500),
    primaryContainer = Color(0xFF5C3D0A),
    onPrimaryContainer = Color(0xFFFFE2B8),
    secondary = Palette.Teal,
    onSecondary = Color(0xFF002019),
    secondaryContainer = Color(0xFF14463D),
    onSecondaryContainer = Color(0xFFC9F3E8),
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Elevated2,
    onSurfaceVariant = Palette.SubText,
    surfaceContainerLowest = Color(0xFF050507),
    surfaceContainerLow = Color(0xFF111116),
    surfaceContainer = Palette.Elevated,
    surfaceContainerHigh = Palette.Elevated2,
    surfaceContainerHighest = Palette.Highlight,
    outline = Color(0xFF4A4A58),
    outlineVariant = Color(0xFF2A2A34),
    inverseSurface = Color(0xFFF2F2F5),
    inverseOnSurface = Color(0xFF16161D),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    )
}

val BrandGradient = Brush.linearGradient(listOf(Palette.Teal, Palette.Amber))

/** A darker shade for backgrounds, keeping white text readable. */
fun Color.deep(amount: Float = 0.45f): Color = lerp(this, Color.Black, amount)

@Composable
fun MyBooksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography) {
        // Text and icons default to black outside a Surface; on this dark app they should be white.
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
