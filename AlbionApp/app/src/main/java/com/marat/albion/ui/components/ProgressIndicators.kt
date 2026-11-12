package com.marat.albion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.theme.LocalReducedMotion

/** Static indeterminate symbols convey waiting without implying a completion percentage. */
@Composable
fun QuietCircularProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, strokeWidth: Dp = 4.dp) {
    if (LocalReducedMotion.current) {
        Icon(AlbionIcons.Hourglass, null, modifier.size(40.dp).progressSemantics(), tint = color)
    } else androidx.compose.material3.CircularProgressIndicator(modifier, color = color, strokeWidth = strokeWidth, strokeCap = StrokeCap.Round)
}

@Composable
fun QuietLinearProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    if (LocalReducedMotion.current) {
        Canvas(modifier.fillMaxWidth().height(4.dp).progressSemantics()) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(trackColor, cornerRadius = r)
            val step = 12.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawRoundRect(color, Offset(x, 0f), Size(minOf(step / 2, size.width - x), size.height), r)
                x += step
            }
        }
    } else androidx.compose.material3.LinearProgressIndicator(modifier, color = color, trackColor = trackColor, strokeCap = StrokeCap.Round, gapSize = 4.dp)
}

/**
 * Круглое кольцо прогресса: дуга от «12 часов» по часовой стрелке со скруглёнными концами.
 * [inset] — отступ от краёв холста до оси линии.
 */
fun DrawScope.drawRingProgress(track: Color?, color: Color, fraction: Float, strokeWidth: Float, inset: Float = strokeWidth / 2) {
    val d = minOf(size.width, size.height) - inset * 2
    if (d <= 0f) return
    val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
    val arc = Size(d, d)
    if (track != null) drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(strokeWidth))
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0f) return
    drawArc(color, -90f, 360f * f, false, topLeft, arc, style = Stroke(strokeWidth, cap = StrokeCap.Round))
}

/** Прежнее имя: геометрия стала круглой, вызовы получают кольцо автоматически. */
@Deprecated("Кольцо теперь круглое", ReplaceWith("drawRingProgress(track, color, fraction, strokeWidth, inset)"))
fun DrawScope.drawSquareProgress(track: Color?, color: Color, fraction: Float, strokeWidth: Float, inset: Float = strokeWidth / 2) =
    drawRingProgress(track, color, fraction, strokeWidth, inset)
