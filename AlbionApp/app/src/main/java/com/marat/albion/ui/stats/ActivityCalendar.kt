package com.marat.albion.ui.stats

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.library.pluralRu
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A dated calendar: fixed numeric bands, exact counts, and non-overlapping touch targets. */
@Composable
internal fun ActivityCalendar(counts: List<Int>, endDay: Long) {
    if (counts.isEmpty()) {
        Text("Нет данных об активности", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val startDay = endDay - counts.lastIndex
    var selectedDay by rememberSaveable { mutableLongStateOf(endDay) }
    val selected = selectedDay.coerceIn(startDay, endDay)
    val selectedCount = counts[(selected - startDay).toInt()].coerceAtLeast(0)
    val formatter = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")) }
    val lead = LocalDate.ofEpochDay(startDay).dayOfWeek.value - 1
    val trailing = 6 - (LocalDate.ofEpochDay(endDay).dayOfWeek.value - 1)
    val dates = List<Long?>(lead) { null } + (startDay..endDay).map { it } + List(trailing) { null }
    val labels = listOf("0", "1–9", "10–24", "25–49", "50+")
    val scheme = MaterialTheme.colorScheme
    val fills = listOf(scheme.surfaceContainerHigh, lerp(scheme.surfaceContainerHigh, scheme.primary, .18f),
        lerp(scheme.surfaceContainerHigh, scheme.primary, .4f),
        lerp(scheme.surfaceContainerHigh, scheme.primary, .7f), scheme.primary)
    fun level(count: Int) = when { count <= 0 -> 0; count < 10 -> 1; count < 25 -> 2; count < 50 -> 3; else -> 4 }
    val selection = "${LocalDate.ofEpochDay(selected).format(formatter)} — $selectedCount ${pluralRu(selectedCount, "ответ", "ответа", "ответов") }"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(selection, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val style = MaterialTheme.typography.labelMedium
            val measured = rememberTextMeasurer().measure("30", style, softWrap = false).size
            val minCell = with(density) { measured.width.toDp() + 16.dp }.coerceAtLeast(48.dp)
            val cellWidth = maxOf(minCell, (maxWidth - 6.dp * 6) / 7)
            val cellHeight = with(density) { measured.height.toDp() + 16.dp }.coerceAtLeast(48.dp)
            val scroll = rememberScrollState()
            val overflow = cellWidth * 7 + 6.dp * 6 > maxWidth
            LaunchedEffect(selected, cellWidth, maxWidth) {
                val col = LocalDate.ofEpochDay(selected).dayOfWeek.value - 1
                val left = with(density) { ((cellWidth + 6.dp) * col).roundToPx() }
                val right = left + with(density) { cellWidth.roundToPx() }
                val viewport = with(density) { maxWidth.roundToPx() }
                if (left < scroll.value) scroll.scrollTo(left)
                else if (right > scroll.value + viewport) scroll.scrollTo(right - viewport)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.horizontalScroll(scroll).semantics {
                    collectionInfo = CollectionInfo(dates.size / 7, 7)
                    isTraversalGroup = true
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").forEach {
                            Box(Modifier.width(cellWidth).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                                Text(it, style = style, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    dates.chunked(7).forEachIndexed { row, week ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            week.forEachIndexed { col, day ->
                                if (day == null) {
                                    Spacer(Modifier.size(cellWidth, cellHeight))
                                } else {
                                    val date = LocalDate.ofEpochDay(day)
                                    val count = counts[(day - startDay).toInt()].coerceAtLeast(0)
                                    val fill = fills[level(count)]
                                    val foreground = if (fill.luminance() > .179f) Color.Black else Color.White
                                    val label = "${date.format(formatter)} — $count ${pluralRu(count, "ответ", "ответа", "ответов") }"
                                    Box(Modifier.size(cellWidth, cellHeight)
                                        .then(if (day == selected) Modifier.border(2.dp, scheme.primary, MaterialTheme.shapes.small) else Modifier)
                                        .selectable(day == selected, role = Role.Button, onClick = { selectedDay = day })
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = label
                                            val states = listOfNotNull(if (day == endDay) "Сегодня" else null,
                                                if (day == selected) "Выбрано" else null)
                                            if (states.isNotEmpty()) stateDescription = states.joinToString(". ")
                                            collectionItemInfo = CollectionItemInfo(row, 1, col, 1)
                                        }.padding(4.dp).background(fill, MaterialTheme.shapes.extraSmall),
                                        contentAlignment = Alignment.Center) {
                                        Text(date.dayOfMonth.toString(), Modifier.clearAndSetSemantics {}, style = style, color = foreground)
                                        if (day == endDay) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                                            .size(3.dp).background(foreground, CircleShape).clearAndSetSemantics {})
                                    }
                                }
                            }
                        }
                    }
                }
                if (overflow) Text("Календарь можно прокрутить по горизонтали", style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
            }
        }
        Text("Ответов за день", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(12.dp).background(fills[i], MaterialTheme.shapes.extraSmall).clearAndSetSemantics {})
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("Точка — сегодня. Нажмите на дату, чтобы увидеть число ответов.", style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant)
    }
}
