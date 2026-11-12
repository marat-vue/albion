package com.marat.albion.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Albion / тёплый редакционный: графит и молоко, слои тоном, без теней. */

/** Нейтрали: тёплый графит и молочная бумага. */
val Ink = Color(0xFF1C1B19)
val Paper = Color(0xFFF6F2EA)

/** Акцент действий — сам контраст: графит на молоке, молоко на графите. */
internal val EstateLight = Ink
internal val EstateDark = Color(0xFFECE6DA)

/** Тёплые «металлы» для служебной графики. */
object Heritage {
    val Champagne = Color(0xFFD8CFBF)
    val Cream = Color(0xFFFBF8F2)
}

/** Сигналы ответа: олива и терракота, всегда вместе с подписью и иконкой — единственный цвет в системе. */
internal val SuccessLight = Color(0xFF4F6B3A)
internal val SuccessDark = Color(0xFFA3BC88)
internal val ErrorLight = Color(0xFFA4483A)
internal val ErrorDark = Color(0xFFE39A8C)

/** Акцентная плита: графитовый монолит с молочным текстом, одинаковый в обеих темах. */
object Hero {
    val Base = Color(0xFF1C1B19)
    val Content = Color(0xFFF6F2EA)
    /** Текст на молочных кнопках внутри плиты. */
    val Deep = Ink
    /** Граница плиты: в светлой теме незаметна, в тёмной отделяет монолит от фона. */
    val Edge = Color(0x24F6F2EA)
    internal val GradientStops = listOf(Color(0xFF26241F), Base, Color(0xFF181715))
    internal val Glow = Color(0xFFF6F2EA).copy(alpha = .05f)
    val Gradient = Brush.linearGradient(GradientStops)
}
