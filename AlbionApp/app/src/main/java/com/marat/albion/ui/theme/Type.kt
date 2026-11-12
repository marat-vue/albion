package com.marat.albion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.marat.albion.R

@OptIn(ExperimentalTextApi::class)
private fun variable(id: Int, weight: FontWeight, italic: Boolean = false, opticalSize: Float? = null) = Font(
    id, weight = weight, style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        *listOfNotNull(FontVariation.weight(weight.weight), opticalSize?.let { FontVariation.Setting("opsz", it) }).toTypedArray(),
    ),
)

private val weights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun literata(opticalSize: Float) = FontFamily(
    weights.map { variable(R.font.literata, it, opticalSize = opticalSize) } +
        weights.map { variable(R.font.literata_italic, it, italic = true, opticalSize = opticalSize) },
)

object AlbionFonts {
    /** Интерфейс: Onest — тёплый гуманистический гротеск с родной кириллицей. */
    val Sans = FontFamily(weights.map { variable(R.font.onest, it) })

    /** Заголовки и крупные термины: Literata с дисплейным оптическим кеглем. */
    val Display = literata(opticalSize = 36f)

    /** Счётчики и индексы: тот же Onest; табличные цифры задаёт стиль ([Numerals]). */
    val Tech = Sans

    /** Транскрипции целиком набраны одной встроенной гарнитурой, включая IPA и диакритику. */
    val Ipa = FontFamily(Font(R.font.noto_sans, FontWeight.Normal))

    /** Книги и учебник: Literata с текстовым оптическим кеглем для долгого чтения. */
    val Reading = literata(opticalSize = 14f)
}

/** Табличные цифры Onest: счётчики не «прыгают» при изменении. */
const val Numerals = "tnum"

private val trim = PlatformTextStyle(includeFontPadding = false)
private val centered = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.Medium, tracking: Double = -0.015) = TextStyle(
    fontFamily = AlbionFonts.Display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.em, platformStyle = trim, lineHeightStyle = centered,
    lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto,
)

private fun sans(size: Float, line: Int, weight: FontWeight, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = AlbionFonts.Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.em, platformStyle = trim, lineHeightStyle = centered,
    lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto, fontFeatureSettings = features,
)

/** Шкала: Literata — от дисплея до titleLarge, Onest — интерфейс и подписи. */
val Typography = Typography(
    displayLarge = display(48, 54, FontWeight.Normal, -0.025),
    displayMedium = display(36, 42, FontWeight.Medium, -0.02),
    displaySmall = display(30, 36, FontWeight.Medium),
    headlineLarge = display(28, 34, FontWeight.Medium),
    headlineMedium = display(25, 31, FontWeight.Medium),
    headlineSmall = display(22, 28, FontWeight.Medium, -0.01),
    titleLarge = display(20, 26, FontWeight.Medium, -0.005),
    titleMedium = sans(16f, 22, FontWeight.SemiBold, -0.005),
    titleSmall = sans(14f, 20, FontWeight.SemiBold),
    bodyLarge = sans(16f, 25, FontWeight.Normal),
    bodyMedium = sans(15f, 22, FontWeight.Normal),
    bodySmall = sans(13f, 19, FontWeight.Normal, 0.005),
    labelLarge = sans(15f, 20, FontWeight.SemiBold, 0.005, Numerals),
    // Подписи — тот же Onest со строчными буквами и табличными цифрами.
    labelMedium = sans(12.5f, 17, FontWeight.Medium, 0.01, Numerals),
    labelSmall = sans(11.5f, 15, FontWeight.Medium, 0.02, Numerals),
)

/** Текст книг и правил: Literata, щедрый интерлиньяж. */
val ReadingStyle = TextStyle(
    fontFamily = AlbionFonts.Reading, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 29.sp,
)

/** Отдельная роль: Noto Sans сохраняет единые метрики всех символов транскрипции. */
val IpaStyle = TextStyle(
    fontFamily = AlbionFonts.Ipa, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp,
    platformStyle = trim, lineHeightStyle = centered,
)
