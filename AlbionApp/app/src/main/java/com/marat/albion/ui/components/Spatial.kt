package com.marat.albion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Informational labels keep the interface font and follow the system text scale. */
@Composable
fun SignalLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun AlbionMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(32.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .14f, h * .8f)
            lineTo(w * .5f, h * .13f)
            lineTo(w * .86f, h * .8f)
            moveTo(w * .32f, h * .65f)
            lineTo(w * .68f, h * .65f)
        }
        drawPath(path, color, style = Stroke(1.7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, 1.9.dp.toPx(), Offset(w * .5f, h * .86f))
    }
}

/**
 * Заголовок модуля. Номер больше не печатается «технической» подписью вида «04 / НАВЫКИ»:
 * редакционный заголовок Literata сам задаёт ритм страницы; [number] оставлен ради совместимости.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ModuleHeading(number: String, title: String, trailing: String? = null, modifier: Modifier = Modifier, compact: Boolean = false) {
    SectionHeading(title, trailing, modifier, leadingGap = 0.dp, compact = compact, editorial = true) {}
}
