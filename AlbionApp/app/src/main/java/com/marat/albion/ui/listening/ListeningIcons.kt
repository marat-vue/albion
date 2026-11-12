package com.marat.albion.ui.listening

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Иконки плеера в стиле AlbionIcons (сетка 24, линия 1.6, прямые углы); общий набор не трогаем. */
internal object ListeningIcons {
    val Pause = pen("Pause", "M8 5v14 M16 5v14")
    val PreviousPhrase = pen("PreviousPhrase", "M6 5v14 M18 5L9 12l9 7z")
    val NextPhrase = pen("NextPhrase", "M18 5v14 M6 5l9 7-9 7z")
    val RepeatPhrase = pen("RepeatPhrase", "M4 12V8h14 M15 5l3 3-3 3 M20 12v4H6 M9 19l-3-3 3-3")
    val Headphones = pen("Listen", "M4 17v-5a8 8 0 0 1 16 0v5 M4 14h3v6H4z M17 14h3v6h-3z")
    val Follow = pen("Follow", "M12 4v12 M6 10l6 6 6-6 M5 20h14")

    private fun pen(name: String, data: String): ImageVector = ImageVector.Builder(
        name = "Albion.Listening.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(data).toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter,
    ).build()
}

/** «4:07» / «1:02:15». */
internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}
