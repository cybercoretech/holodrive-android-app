package dev.cybercore.holodrive.photos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.graphics.toColorInt

private val mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

@Composable
fun PhotoTheme(state: PhotoState, content: @Composable () -> Unit) {
    val palette = state.catalog.themes.firstOrNull { it.id == (state.themeChoice ?: state.catalog.active) }?.palette.orEmpty()
    fun color(key: String, fallback: Long): Color = palette[key]?.let {
        runCatching { Color("#$it".toColorInt()) }.getOrNull()
    } ?: Color(fallback)
    val bg = color("bg", 0xFF07070D)
    val fg = color("white", 0xFFE6E6F0)
    val panel = color("panel", 0xFF0F0F1A)
    fun readable(value: Color): Color {
        var result = value
        repeat(12) {
            val contrast = (maxOf(result.luminance(), panel.luminance()) + .05f) / (minOf(result.luminance(), panel.luminance()) + .05f)
            if (contrast < 4.5f) result = lerp(result, fg, .2f)
        }
        return result
    }
    val primary = readable(color("cyan", 0xFF00F0FF))
    val scheme = if (bg.luminance() > .5f) lightColorScheme() else darkColorScheme()
    val typography = Typography()
    MaterialTheme(
        colorScheme = scheme.copy(
            primary = primary, onPrimary = bg,
            secondary = readable(color("hot_pink", 0xFFFF2BD6)), onSecondary = bg,
            tertiary = readable(color("purple", 0xFF9B5CFF)),
            background = bg, onBackground = fg, surface = panel, onSurface = fg,
            surfaceVariant = lerp(panel, fg, .06f), onSurfaceVariant = readable(color("muted", 0xFF8888A8)),
            outline = color("line", 0xFF262640), outlineVariant = color("line", 0xFF262640),
            surfaceContainer = panel, surfaceContainerHigh = lerp(panel, fg, .04f),
            error = readable(color("red", 0xFFFF3860)),
        ),
        typography = typography.copy(
            headlineLarge = typography.headlineLarge.copy(fontFamily = mono, fontWeight = FontWeight.Bold),
            headlineMedium = typography.headlineMedium.copy(fontFamily = mono, fontWeight = FontWeight.Bold),
            titleLarge = typography.titleLarge.copy(fontFamily = mono, fontWeight = FontWeight.Bold),
            titleMedium = typography.titleMedium.copy(fontFamily = mono),
            bodyLarge = typography.bodyLarge.copy(fontFamily = mono),
            bodyMedium = typography.bodyMedium.copy(fontFamily = mono),
            bodySmall = typography.bodySmall.copy(fontFamily = mono),
            labelLarge = typography.labelLarge.copy(fontFamily = mono),
            labelMedium = typography.labelMedium.copy(fontFamily = mono),
            labelSmall = typography.labelSmall.copy(fontFamily = mono),
        ), content = content,
    )
}

@Composable
fun GridBackdrop() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxSize()) {
        drawRect(colors.background)
        drawRect(Brush.radialGradient(listOf(colors.primary.copy(alpha = .09f), Color.Transparent), center = Offset(0f, 0f), radius = size.width))
        drawRect(Brush.radialGradient(listOf(colors.secondary.copy(alpha = .08f), Color.Transparent), center = Offset(size.width, size.height), radius = size.width))
        val horizon = size.height * .7f
        for (i in -6..6) {
            drawLine(colors.primary.copy(alpha = .08f), Offset(size.width / 2 + i * 25f, horizon), Offset(size.width / 2 + i * size.width / 4, size.height))
        }
        for (i in 1..9) {
            val y = horizon + (size.height - horizon) * (i * i / 81f)
            drawLine(colors.secondary.copy(alpha = .09f), Offset(0f, y), Offset(size.width, y))
        }
    }
}
