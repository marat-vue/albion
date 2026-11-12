package com.marat.albion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.marat.albion.data.settings.ThemeMode

/** Светлая: молочная бумага, слои тоном (#FBF8F2 → #EFEAE0 → #E4DED2), графитовые чернила. */
private val LightScheme = lightColorScheme(
    primary = EstateLight, onPrimary = Paper,
    primaryContainer = Color(0xFFE4DED2), onPrimaryContainer = Ink,
    inversePrimary = EstateDark,
    secondary = Color(0xFF4A4741), onSecondary = Paper,
    secondaryContainer = Color(0xFFEFEAE0), onSecondaryContainer = Color(0xFF2A2825),
    tertiary = SuccessLight, onTertiary = Paper,
    tertiaryContainer = Color(0xFFE6EBDD), onTertiaryContainer = Color(0xFF2C3D20),
    error = ErrorLight, onError = Paper,
    errorContainer = Color(0xFFF3E1DC), onErrorContainer = Color(0xFF5C2219),
    background = Paper, onBackground = Color(0xFF2A2825),
    surface = Paper, onSurface = Color(0xFF2A2825),
    surfaceVariant = Color(0xFFEFEAE0), onSurfaceVariant = Color(0xFF625E57),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFE4DED2), surfaceBright = Color(0xFFFBF8F2),
    surfaceContainerLowest = Color(0xFFFDFBF7),
    surfaceContainerLow = Color(0xFFFBF8F2),
    surfaceContainer = Color(0xFFF3EEE5),
    surfaceContainerHigh = Color(0xFFEFEAE0),
    surfaceContainerHighest = Color(0xFFE4DED2),
    inverseSurface = Ink, inverseOnSurface = Paper,
    outline = Color(0xFF7E786D), outlineVariant = Color(0xFFDDD6C8),
    scrim = Ink,
)

/** Тёмная: тёплый уголь, слои #1D1C1A → #272523, молочный текст. */
private val DarkScheme = darkColorScheme(
    primary = EstateDark, onPrimary = Color(0xFF141312),
    primaryContainer = Color(0xFF312F2C), onPrimaryContainer = EstateDark,
    inversePrimary = EstateLight,
    secondary = Color(0xFFC9C2B6), onSecondary = Color(0xFF141312),
    secondaryContainer = Color(0xFF272523), onSecondaryContainer = EstateDark,
    tertiary = SuccessDark, onTertiary = Color(0xFF1B2513),
    tertiaryContainer = Color(0xFF232A1D), onTertiaryContainer = Color(0xFFD5E2C6),
    error = ErrorDark, onError = Color(0xFF3A140D),
    errorContainer = Color(0xFF2F1F1B), onErrorContainer = Color(0xFFF6D6CF),
    background = Color(0xFF141312), onBackground = EstateDark,
    surface = Color(0xFF141312), onSurface = EstateDark,
    surfaceVariant = Color(0xFF272523), onSurfaceVariant = Color(0xFFA39D92),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF141312), surfaceBright = Color(0xFF312F2C),
    surfaceContainerLowest = Color(0xFF0F0E0D),
    surfaceContainerLow = Color(0xFF1D1C1A),
    surfaceContainer = Color(0xFF222120),
    surfaceContainerHigh = Color(0xFF272523),
    surfaceContainerHighest = Color(0xFF312F2C),
    inverseSurface = EstateDark, inverseOnSurface = Color(0xFF141312),
    outline = Color(0xFF857F74), outlineVariant = Color(0xFF3A3733),
    scrim = Color.Black,
)

private fun scheme(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

/** Functional boundaries share Material's outline; outlineVariant remains decorative. */
val ColorScheme.controlBorder: Color get() = outline

/** Выбор — полная инверсия: графитовая плашка на молоке, молочная на угле. */
val ColorScheme.selectedContainer: Color get() = inverseSurface
val ColorScheme.selectedBorder: Color get() = inverseSurface
val ColorScheme.selectedContent: Color get() = inverseOnSurface

/** Мягкая геометрия: 8 dp у полей и кнопок, 12 dp у карточек, 20 dp у шторок; чипы — капсулы ([Capsule]). */
private val AlbionShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Капсула для чипов, бейджей и индикатора навигации. */
val Capsule = RoundedCornerShape(percent = 50)

/** Тёмная ли сейчас тема приложения (с учётом выбора в настройках). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AlbionTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = scheme(darkTheme)
    CompositionLocalProvider(LocalDarkTheme provides darkTheme, LocalContentColor provides colors.onSurface) {
        MaterialTheme(colorScheme = colors, typography = Typography, shapes = AlbionShapes, content = content)
    }
}
