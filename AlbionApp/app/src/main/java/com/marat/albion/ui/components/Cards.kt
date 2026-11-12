package com.marat.albion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.marat.albion.ui.theme.Hero
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp

/**
 * Акцентная плита: графитовый монолит, одинаковый в обеих темах.
 * Внутри действует локальная схема — белый текст и «инверсные» кнопки. Одна на экран.
 */
@Composable
fun BoundVolume(modifier: Modifier = Modifier, compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(if (compact) 20.dp else 24.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp), content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onHero = Hero.Content
    val heroScheme = scheme.copy(
        primary = onHero, onPrimary = Hero.Deep,
        background = onHero, onBackground = Hero.Deep,
        surface = Hero.Base, onSurface = onHero, onSurfaceVariant = onHero,
        surfaceContainerLow = Hero.Base,
        inverseSurface = Hero.Base, inverseOnSurface = onHero,
        surfaceContainerHigh = onHero.copy(alpha = .16f), outline = onHero, outlineVariant = onHero.copy(alpha = .22f),
    )
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = Color.Transparent, contentColor = onHero,
        border = BorderStroke(1.dp, Hero.Edge)) {
        MaterialTheme(colorScheme = heroScheme) {
            CompositionLocalProvider(LocalContentColor provides onHero) {
                Column(Modifier.background(Hero.Gradient).drawBehind {
                    // Едва заметный тёплый блик в углу монолита.
                    drawRect(Brush.radialGradient(listOf(Hero.Glow, Color.Transparent),
                        center = Offset(size.width * .95f, 0f), radius = size.maxDimension * .7f))
                }
                    .padding(contentPadding),
                    verticalArrangement = verticalArrangement, content = content)
            }
        }
    }
}

@Composable
fun CatalogueTab(label: String, modifier: Modifier = Modifier) {
    Eyebrow(label, modifier)
}

@Composable
fun FolioDivider(modifier: Modifier = Modifier, label: String? = null) {
    if (label != null) Eyebrow(label, modifier) else
        Box(modifier.fillMaxWidth().height(1.dp).folioRule(MaterialTheme.colorScheme.outline))
}

fun romanNumeral(value: Int): String {
    if (value !in 1..3999) return value.toString()
    var rest = value
    return buildString {
        for ((number, glyph) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (rest >= number) { append(glyph); rest -= number }
        }
    }
}
