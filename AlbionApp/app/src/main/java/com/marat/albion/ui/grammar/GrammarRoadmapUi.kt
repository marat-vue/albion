package com.marat.albion.ui.grammar

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import com.marat.albion.data.db.GrammarProgressEntity
import com.marat.albion.data.grammar.GrammarBook
import com.marat.albion.data.grammar.GrammarNextStep
import com.marat.albion.data.grammar.GrammarReview
import com.marat.albion.data.grammar.Topic
import com.marat.albion.ui.components.*
import com.marat.albion.ui.theme.*

internal data class GrammarStage(val level: String, val name: String, val title: String, val topics: List<Topic>)

internal fun grammarStages(book: GrammarBook): List<GrammarStage> = listOf(
    Triple("A1", "Foundations", "Основы английского"),
    Triple("A2", "Everyday English", "Английский на каждый день"),
    Triple("B1", "An Independent Voice", "Самостоятельная речь"),
    Triple("B2", "Precision & Fluency", "Точность и свобода"),
    Triple("C1", "The Art of Expression", "Искусство выражать мысли"),
).map { (level, name, title) -> GrammarStage(level, name, title, book.learningOrder.filter { it.level == level }) }
    .filter { it.topics.isNotEmpty() }

@Composable
internal fun GrammarAtlas(
    stages: List<GrammarStage>, active: String?, progress: Map<String, GrammarProgressEntity>,
    total: Int, mastered: Int, onSelect: (String) -> Unit,
) {
    val feedback = LocalFeedback.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val titleStyle = MaterialTheme.typography.titleMedium
            val countStyle = MaterialTheme.typography.labelMedium
            val naturalWidth = stages.maxOfOrNull { stage ->
                val done = stage.topics.count { progress[it.id]?.masteredAt != null }
                maxOf(measurer.measure(stage.level, titleStyle, softWrap = false).size.width,
                    measurer.measure("$done/${stage.topics.size}", countStyle, softWrap = false).size.width)
            } ?: 0
            val minWidth = with(density) { naturalWidth.toDp() + 24.dp }.coerceAtLeast(56.dp)
            val stageWidth = maxOf(minWidth, (maxWidth - 6.dp * (stages.size - 1).coerceAtLeast(0)) / stages.size.coerceAtLeast(1))
            val scroll = rememberScrollState()
            LaunchedEffect(active, stageWidth, density) {
                val index = stages.indexOfFirst { it.level == active }.coerceAtLeast(0)
                scroll.scrollTo(with(density) { ((stageWidth + 6.dp) * index).roundToPx() })
            }
            Row(Modifier.horizontalScroll(scroll).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stages.forEach { stage ->
                    val done = stage.topics.count { progress[it.id]?.masteredAt != null }
                    val selected = active == stage.level
                    val fill by androidx.compose.animation.animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.selectedContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "stage")
                    Column(Modifier.width(stageWidth).heightIn(min = 64.dp)
                        .background(fill, MaterialTheme.shapes.small).selectionSurface(selected, MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected, role = Role.Tab, onClick = { feedback.selection(); onSelect(stage.level) })
                        .semantics { stateDescription = "Освоено $done из ${stage.topics.size} тем" }
                        .padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(stage.level, style = titleStyle, maxLines = 1, softWrap = false,
                            color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurface)
                        Text("$done/${stage.topics.size}", style = countStyle, maxLines = 1, softWrap = false,
                            color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Освоение по уровню уже на плитках, по этапу — в заголовке маршрута; отдельная строка повторяла их.
    }
}

/** Следующий шаг по учебнику — инверсный акцент экрана: урок крупно и одно главное действие. */
@Composable
internal fun GrammarNextCard(step: GrammarNextStep.Step, onClick: () -> Unit, onRead: () -> Unit) {
    val titleStyle = MaterialTheme.typography.headlineSmall
    val measurer = rememberTextMeasurer()
    val wordWidth = with(LocalDensity.current) {
        step.topic.title.split(Regex("\\s+")).maxOf { measurer.measure(it, titleStyle).size.width }.toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        val inset = ((maxWidth - wordWidth) / 2).coerceIn(12.dp, 24.dp)
        BoundVolume(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = inset, vertical = 24.dp)) {
            Text("Следующий шаг · ${step.label}", style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.LocalContentColor.current)
            Text("Урок %d · %s".format(step.topic.number, step.topic.level.orEmpty()), style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.LocalContentColor.current)
            Text(step.topic.title, style = titleStyle)
            com.marat.albion.ui.components.PrimaryButton(step.action, onClick, trailingIcon = AlbionIcons.ArrowForward, inverse = true)
            if (step.kind != GrammarNextStep.Kind.Read) {
                val onInk = MaterialTheme.colorScheme.inverseOnSurface
                TextButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("Читать урок", color = onInk) }
            }
        }
    }
}

@Composable
internal fun GrammarViewPicker(selected: Int, due: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Маршрут", "Закладки", "Повторить · $due")
    OptionPicker("Раздел учебника", labels, selected, onSelect, Modifier.padding(top = 8.dp))
}

@Composable
internal fun GrammarStageHeading(stage: GrammarStage, index: Int, done: Int, read: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stage.level, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(stage.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("Прочитано $read · освоено $done из ${stage.topics.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GrammarRouteLesson(
    topic: Topic, progress: GrammarProgressEntity?, current: Boolean, selected: Boolean,
    first: Boolean, last: Boolean, chapter: String?, now: Long, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mastered = progress?.masteredAt != null
    val read = progress?.readAt != null
    val due = GrammarReview.plan(progress)?.dueAt?.let { it <= now } == true
    val status = when {
        due -> "К повторению"
        mastered -> "Освоено"
        current && read -> "Проверьте знания"
        current -> "Ваш следующий шаг"
        progress?.quizAt != null -> "Проверка: ${progress.quizCorrect} из ${progress.quizTotal}"
        read -> "Прочитано"
        else -> "Изучить тему"
    }
    val feedback = LocalFeedback.current
    val emphasis = current || selected
    val line = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val numberStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val numberSize = measurer.measure(topic.number.toString(), numberStyle, softWrap = false).size
    val markerWidth = with(density) { numberSize.width.toDp() + 12.dp }.coerceAtLeast(36.dp)
    val markerHeight = with(density) { numberSize.height.toDp() + 8.dp }.coerceAtLeast(36.dp)
    val columnWidth = (markerWidth + 8.dp).coerceAtLeast(44.dp)
    val minimumTextWidth = maxOf(
        topic.title.split(Regex("\\s+")).maxOf { measurer.measure(it, MaterialTheme.typography.titleMedium).size.width },
        status.split(Regex("\\s+")).maxOf { measurer.measure(it, MaterialTheme.typography.labelSmall).size.width },
    ) + with(density) { 24.dp.roundToPx() }
    Layout(modifier = modifier.fillMaxWidth().heightIn(min = 92.dp).drawBehind {
        val x = columnWidth.toPx() / 2; val y = 10.dp.toPx() + markerHeight.toPx() / 2
        if (!first) drawLine(line, Offset(x, 0f), Offset(x, y), .75.dp.toPx())
        if (!last) drawLine(line, Offset(x, y), Offset(x, size.height), .75.dp.toPx(),
            pathEffect = if (mastered) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    }.clickable(role = Role.Button, onClick = { feedback.selection(); onClick() })
        .semantics(mergeDescendants = true) { stateDescription = status; this.selected = selected }, content = {
        Box(Modifier.padding(top = 10.dp).width(columnWidth).height(markerHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.size(markerWidth, markerHeight).background(if (emphasis) MaterialTheme.colorScheme.primary else if (mastered) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
                .border(1.dp, if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                if (mastered) Icon(AlbionIcons.Check, null, tint = if (emphasis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(17.dp))
                else Text(topic.number.toString(), style = numberStyle, maxLines = 1, softWrap = false,
                    color = if (emphasis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(bottom = 12.dp)
            .background(if (emphasis) MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.medium)
            .selectionSurface(selected)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.selectedContent else if (emphasis || due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (progress?.bookmarked == true) Icon(AlbionIcons.Bookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.height(5.dp))
            Text(topic.title, style = MaterialTheme.typography.titleMedium, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurface)
            if (chapter != null) {
                Spacer(Modifier.height(5.dp))
                Text(chapter, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!due) GrammarReview.plan(progress)?.let { plan ->
                Spacer(Modifier.height(4.dp))
                Text("Повторение: ${grammarReviewDate(plan.dueAt, now)}", style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val marker = children[0].measure(loose)
        val gap = 13.dp.roundToPx()
        val inline = marker.width + gap + minimumTextWidth <= constraints.maxWidth
        val textWidth = if (inline) constraints.maxWidth - marker.width - gap else constraints.maxWidth
        val lesson = children[1].measure(loose.copy(minWidth = textWidth, maxWidth = textWidth))
        val textTop = if (inline) 0 else marker.height + 8.dp.roundToPx()
        val height = constraints.constrainHeight(maxOf(marker.height, textTop + lesson.height))
        layout(constraints.maxWidth, height) {
            marker.placeRelative(0, 0)
            lesson.placeRelative(if (inline) marker.width + gap else 0, textTop)
        }
    }
}
