package com.marat.albion.ui.games

import com.marat.albion.ui.theme.Capsule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import com.marat.albion.data.audio.ShadowReport
import kotlin.math.abs
import kotlin.math.ceil

/** Слово диктора на шкале графика: кадры [from, to] от начала фразы. */
internal data class ChartWord(val text: String, val from: Int, val to: Int)

/** Общая шкала относительной высоты; время записи уже сопоставлено с диктором. [words] подписываются под кривой. */
@Composable
internal fun MelodyChart(report: ShadowReport, words: List<ChartWord> = emptyList()) {
    val original = remember(report) { preparePitchContour(report.originalPitch, report.originalEnergy) }
    val mine = remember(report) { preparePitchContour(report.minePitch, report.mineEnergy) }

    val fg = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val center = MaterialTheme.colorScheme.outline
    val band = MaterialTheme.colorScheme.surfaceContainerLow

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (original.available || mine.available) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (original.available) MelodyLegend(dashed = false, label = "Диктор")
                if (mine.available) MelodyLegend(dashed = true, label = "Вы")
            }
            Row(
                Modifier.fillMaxWidth().background(band, MaterialTheme.shapes.small)
                    .border(.75.dp, grid, MaterialTheme.shapes.small)
                    .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.height(144.dp).padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Выше", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ниже", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Canvas(Modifier.fillMaxWidth().height(144.dp).semantics {
                        contentDescription = "Мелодика: подъёмы и спады тона от начала к концу фразы"
                    }) {
                        val points = (original.segments + mine.segments).flatten()
                        val limit = maxOf(4f, ceil((points.maxOfOrNull { abs(it.semitones) } ?: 4f) / 2f) * 2f)
                        val frames = maxOf(report.originalPitch.size, report.minePitch.size, 2)
                        val inset = 4.dp.toPx()
                        val width = (size.width - 2 * inset).coerceAtLeast(0f)
                        val height = size.height - 2 * inset
                        fun x(frame: Int) = inset + frame.toFloat() / (frames - 1) * width
                        fun y(semitones: Float) = inset + (1f - semitones / limit) * height / 2

                        for (level in listOf(-1f, 0f, 1f)) {
                            val yy = y(level * limit)
                            drawLine(
                                if (level == 0f) center else grid, Offset(inset, yy), Offset(size.width - inset, yy), 1.dp.toPx(),
                                pathEffect = if (level == 0f) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
                            )
                        }
                        fun drawContour(contour: PitchContour, dashed: Boolean) {
                            for (segment in contour.segments) {
                                val path = Path()
                                path.moveTo(x(segment.first().frame), y(segment.first().semitones))
                                // Контрольные точки лежат между соседними измерениями: кривая
                                // не создаёт новых пиков и не пересекает настоящие разрывы.
                                for (i in 1 until segment.size) {
                                    val a = segment[i - 1]
                                    val b = segment[i]
                                    val midX = (x(a.frame) + x(b.frame)) / 2
                                    path.cubicTo(midX, y(a.semitones), midX, y(b.semitones), x(b.frame), y(b.semitones))
                                }
                                drawPath(
                                    path, if (dashed) muted else fg,
                                    style = Stroke(
                                        width = (if (dashed) 2.5.dp else 3.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
                                    ),
                                )
                            }
                        }
                        // Границы слов — тонкие вертикали: видно, на какое слово приходится подъём или спад.
                        for (word in words.drop(1)) {
                            val xx = x(word.from.coerceIn(0, frames - 1))
                            drawLine(grid, Offset(xx, inset), Offset(xx, size.height - inset), 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                        }
                        drawContour(mine, dashed = true)
                        drawContour(original, dashed = false)
                    }
                    if (words.isNotEmpty()) WordLabels(words, maxOf(report.originalPitch.size, report.minePitch.size, 2), inset = 4.dp)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Начало", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Конец", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Середина — обычный тон каждого голоса. Разрывы — паузы и звуки без тона.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!original.available) {
            Text(
                "В записи диктора мало участков с различимым тоном. Сравните интонацию на слух.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!mine.available) {
            Text(
                "Не удалось уверенно определить тон вашей записи. Попробуйте записать ещё раз ближе к микрофону.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Подписи слов под центрами их отрезков; если соседние не помещаются, следующая уходит во второй ряд. */
@Composable
private fun WordLabels(words: List<ChartWord>, frames: Int, inset: androidx.compose.ui.unit.Dp) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() + 2.dp }
    Canvas(Modifier.fillMaxWidth().height(rowHeight * 2).padding(top = 2.dp).semantics {
        contentDescription = "Слова диктора: " + words.joinToString(" ") { it.text }
    }) {
        val left = inset.toPx()
        val width = (size.width - 2 * left).coerceAtLeast(1f)
        val ends = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val gap = 6.dp.toPx()
        for (word in words) {
            val layout = measurer.measure(word.text, style, maxLines = 1)
            val center = left + (word.from + word.to) / 2f / (frames - 1) * width
            val x0 = (center - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            val row = if (x0 >= ends[0] + gap) 0 else if (x0 >= ends[1] + gap) 1 else if (ends[0] <= ends[1]) 0 else 1
            ends[row] = x0 + layout.size.width
            drawText(layout, topLeft = Offset(x0, row * rowHeight.toPx()))
        }
    }
}

@Composable
private fun MelodyLegend(dashed: Boolean, label: String) {
    val color = if (dashed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer, Capsule).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 24.dp, height = 8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Butt,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
