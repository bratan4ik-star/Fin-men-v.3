package ua.bella.finance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Токени Nocturne (темна) + світла тема з прототипу. */
data class BellaColors(
    val bg: Color, val surface: Color, val text: Color, val divider: Color,
    val n400: Color, val n500: Color, val n600: Color, val n700: Color, val n800: Color,
    val accent: Color, val accent300: Color, val accent400: Color, val accent800: Color,
    val pos: Color, val neg: Color, val light: Boolean,
    /** Бурштиновий: поле, яке ще треба заповнити. */
    val warn: Color = Color(0xFFFBBF24),
)

val DarkColors = BellaColors(
    bg = Color(0xFF161826), surface = Color(0xFF232532), text = Color(0xFFE9E9ED), divider = Color(0xFFE9E9ED).copy(alpha = .16f),
    n400 = Color(0xFFB2B6CA), n500 = Color(0xFF9397AB), n600 = Color(0xFF75798C), n700 = Color(0xFF595D6C), n800 = Color(0xFF3F424D),
    accent = Color(0xFF9184D9), accent300 = Color(0xFFD2CEFD), accent400 = Color(0xFFB5ABFC), accent800 = Color(0xFF423A6A),
    pos = Color(0xFF4ADE80), neg = Color(0xFFF87171), light = false,
)
val LightColors = BellaColors(
    bg = Color(0xFFF4F4F7), surface = Color.White, text = Color(0xFF1B1D2B), divider = Color(0xFF1B1D2B).copy(alpha = .12f),
    n400 = Color(0xFF5B6075), n500 = Color(0xFF666B82), n600 = Color(0xFF9A9EB2), n700 = Color(0xFFC4C7D4), n800 = Color(0xFFD9DBE4),
    accent = Color(0xFF9184D9), accent300 = Color(0xFF5B4DB8), accent400 = Color(0xFF6A5CC7), accent800 = Color(0xFFE6E2F8),
    pos = Color(0xFF15803D), neg = Color(0xFFB91C1C), light = true, warn = Color(0xFFB45309),
)
val LocalBella = staticCompositionLocalOf { DarkColors }
val BlueC = Color(0xFF60A5FA); val TealC = Color(0xFF2DD4BF); val GoldC = Color(0xFFFBBF24); val PurpleC = Color(0xFFC084FC)

@Composable
fun BellaTheme(light: Boolean, content: @Composable () -> Unit) {
    val c = if (light) LightColors else DarkColors
    val scheme = if (light) lightColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    else darkColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    CompositionLocalProvider(LocalBella provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}
val C: BellaColors @Composable get() = LocalBella.current
