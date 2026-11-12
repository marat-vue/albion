package com.marat.albion.ui.stats

import com.marat.albion.ui.theme.Capsule

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.marat.albion.ui.theme.appear
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.marat.albion.ui.components.gutter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.ui.components.HeroCard
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.DayCount
import com.marat.albion.data.repo.Stats
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.Hairline
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.components.ScreenHeader
import com.marat.albion.ui.components.SectionHeader
import com.marat.albion.ui.components.SettingsShortcut
import com.marat.albion.ui.games.Game
import com.marat.albion.ui.library.pluralRu
import androidx.compose.foundation.border
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.components.StatPair
import com.marat.albion.ui.components.StatMetric
import com.marat.albion.ui.theme.AnimatedNumber
import com.marat.albion.ui.theme.Easing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StatsScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val stats by remember { c.study.observeStats() }.collectAsStateWithLifecycle(null)
    val adviceVm = viewModel { StudyAdviceViewModel(c) }
    val advice by adviceVm.advice.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(horizontal = gutter).padding(bottom = 32.dp + com.marat.albion.ui.components.bottomChrome),
    ) {
        Box(Modifier) { ScreenHeader("Прогресс", compact = true, trailing = { SettingsShortcut(navigator::openSettings) }) }
        val s = stats ?: run { com.marat.albion.ui.components.LoadingState("Собираем прогресс"); return@Column }

        // в день рекорда — стоит на карточке достижения.
        val milestone = streakMilestone(s.streak)

        val month = days(s.daily, s.today - 29, s.today)
        Panel(Modifier.fillMaxWidth().padding(top = 8.dp).appear(2)) {
            com.marat.albion.ui.components.SignalLabel("Регулярность")
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                com.marat.albion.ui.theme.AnimatedNumber(s.streak, MaterialTheme.typography.displayLarge)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                    Text("серия дней", style = MaterialTheme.typography.titleMedium)
                    Text(milestone?.first ?: if (s.streak == 0) "Начните сегодня" else "Не прерывайте серию",
                        style = MaterialTheme.typography.bodySmall, color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f))
                }
                if (LocalDensity.current.fontScale <= 1.3f) Spacer(Modifier.size(80.dp))
            }
            Spacer(Modifier.height(18.dp))
            WeekStrip(month.takeLast(7))
        }
        Spacer(Modifier.height(12.dp))
        StatPair(StatMetric(s.answersToday, "ответов сегодня"), StatMetric((s.timeTodayMs / 60000).toInt(), "минут сегодня"), Modifier.appear(3))
        Spacer(Modifier.height(12.dp))
        StatPair(StatMetric(s.retention?.let { (it * 100).toInt() }, "вспомнили · 30 дней", "%"),
            StatMetric(s.totalReviews + s.totalGameAnswers, "ответов за всё время"), Modifier.appear(4))
        Text("Ответы в карточках и упражнениях.", Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.retention == null) Text("Удержание появится после повторений изученных карточек.", Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier) {
            SectionHeader("Активность · 30 дней", trailing = "${month.sum()} ${pluralRu(month.sum(), "ответ", "ответа", "ответов")}")
            ActivityCalendar(month, s.today)
            Text("Ответы в карточках и упражнениях", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }

        advice?.let { next ->
            HeroCard(Modifier.fillMaxWidth().padding(top = 24.dp), contentPadding = PaddingValues(20.dp)) {
                Eyebrow("Следующее занятие")
                Spacer(Modifier.height(9.dp))
                Text(next.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(next.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                PrimaryButton(next.action, onClick = { navigator.open(next.destination) })
            }
        }

        Column(Modifier) {
            GameBreakdown(s)
        }

        Column(Modifier) {
            SectionHeader("Прогноз", trailing = "14 дн")
            BarChart(days(s.forecast, s.today, s.today + 13), startDay = s.today, forecast = true)
            Text("Карточки с уже назначенной датой повторения. Новые ответы могут изменить прогноз.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(Modifier) {
            SectionHeader("Словари")
            DeckBreakdown(s)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Всего ответов: ${s.totalReviews + s.totalGameAnswers} · карточки: ${s.totalReviews}, практика: ${s.totalGameAnswers}. " +
                "Удержание FSRS — доля повторений карточек, на которые вы ответили не «Снова».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameBreakdown(s: Stats) {
    SectionHeader("Практика · за всё время", trailing = "${s.totalGameAnswers} ${pluralRu(s.totalGameAnswers, "ответ", "ответа", "ответов")}")
    Spacer(Modifier.height(12.dp))
    // «Ответов сегодня» уже есть вверху экрана — здесь только то, что относится к практике.
    StatPair(StatMetric(s.games.sumOf { it.wrong }, "ошибок за всё время"),
        StatMetric(s.gameAccuracy?.let { (it * 100).toInt() }, "точность · всё время", "%"))
    Spacer(Modifier.height(18.dp))
    val totals = s.games.associateBy { it.game }
    var showAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val used = Game.entries.filter { (totals[it.name]?.answers ?: 0) > 0 }
    (if (showAll) Game.entries.toList() else used).forEach { game ->
        val result = totals[game.name]
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                if (result == null || result.answers == 0) "Пока нет ответов"
                else "${result.correct} из ${result.answers} верно · ${(result.accuracy!! * 100).toInt()}% · ошибок: ${result.wrong}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Hairline()
    }
    com.marat.albion.ui.components.TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Только мои упражнения" else "Все упражнения") }
    Text(
        "Здесь учтены упражнения и проверки тем за всё время.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
    )
}

private fun days(source: List<DayCount>, from: Long, to: Long): List<Int> {
    val map = source.associate { it.day to it.count }
    return (from..to).map { map[it] ?: 0 }
}

/** Один график — одна область касания: узкие столбики не перекрывают соседние touch targets. */
@Composable
private fun BarChart(values: List<Int>, startDay: Long, forecast: Boolean = false) {
    if (values.none { it > 0 }) {
        Panel(contentPadding = PaddingValues(16.dp)) {
            Text(if (forecast) "Пока нет запланированных повторений" else "История появится после первого занятия",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(if (forecast) "Начните изучать карточки — здесь появится расписание."
                else "Здесь будут видны ответы по дням за последние 30 дней.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val max = values.maxOrNull()!!.coerceAtLeast(1)
    var selectedIndex by rememberSaveable(startDay) { mutableIntStateOf(if (forecast) 0 else values.lastIndex) }
    val selected = selectedIndex.coerceIn(values.indices)
    val formatter = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    val date = LocalDate.ofEpochDay(startDay + selected).format(formatter)
    val selectionLabel = "$date · ${values[selected]} ${if (forecast) "к повторению" else pluralRu(values[selected], "ответ", "ответа", "ответов") }"
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        grow.animateTo(1f, tween(if (reduced) 0 else 300, easing = Easing.Emphasized))
    }
    Text(selectionLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().height(104.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (forecast) "График предстоящих повторений" else "График активности"
                stateDescription = selectionLabel
                customActions = listOf(
                    CustomAccessibilityAction("Предыдущий день") { selectedIndex = (selected - 1).coerceAtLeast(0); true },
                    CustomAccessibilityAction("Следующий день") { selectedIndex = (selected + 1).coerceAtMost(values.lastIndex); true },
                )
            }
            .pointerInput(values) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    selectedIndex = (change.position.x / size.width * values.size).toInt().coerceIn(values.indices)
                }
            }
            .pointerInput(values) {
                detectTapGestures { offset ->
                    selectedIndex = (offset.x / size.width * values.size).toInt().coerceIn(values.indices)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i == selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.BottomCenter) {
                val h = if (v == 0) 0.015f else (v.toFloat() / max).coerceAtLeast(0.04f)
                val color = when {
                    v == 0 -> MaterialTheme.colorScheme.outlineVariant
                    forecast && i == selected -> MaterialTheme.colorScheme.secondary
                    forecast -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                    i == selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                }
                Box(Modifier.fillMaxWidth().fillMaxHeight(h * if (reduced) 1f else grow.value).background(color, MaterialTheme.shapes.extraSmall))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (if (LocalDensity.current.fontScale > 1.3f) listOf(0, values.lastIndex) else (values.indices.step(7).toList() + values.lastIndex).distinct()).forEach { index ->
            Text(LocalDate.ofEpochDay(startDay + index).format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row {
        Text(if (forecast) "сегодня" else "29 дней назад", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("макс. $max", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(if (forecast) "+13 дней" else "сегодня", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Коснитесь столбика, чтобы выбрать день", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun DeckBreakdown(s: Stats) {
    val byId = s.counts.associateBy { it.deck }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Deck.entries.forEach { deck ->
            val c = byId[deck.id] ?: return@forEach
            val young = (c.started - c.learning - c.mature).coerceAtLeast(0)
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(deck.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("${c.started} / ${c.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (c.started < c.total) "Начато ${c.started} карточек · следующий рубеж ${minOf(c.total, (c.started / 10 + 1) * 10)}" else "Начат весь словарь · ${c.started} карточек",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(10.dp).clip(Capsule).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    val total = c.total.coerceAtLeast(1).toFloat()
                    if (c.learning > 0) Box(Modifier.weight(c.learning / total).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
                    if (young > 0) Box(Modifier.weight(young / total).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    if (c.mature > 0) Box(Modifier.weight(c.mature / total).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    val rest = (c.total - c.started) / total
                    if (rest > 0f) Spacer(Modifier.weight(rest))
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend(MaterialTheme.colorScheme.secondary, "Учу ${c.learning}")
                    Legend(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), "Повтор $young")
                    Legend(MaterialTheme.colorScheme.primary, "Выучено ${c.mature}")
                    Legend(MaterialTheme.colorScheme.surfaceContainerHighest, "Новые ${(c.total - c.started).coerceAtLeast(0)}")
                }
            }
        }
    }
    if (s.counts.isEmpty()) Text("Материалы ещё не загружены.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(4.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Подпись к серии дней: с недели без пропусков. */
private fun streakMilestone(streak: Int): Pair<String, String>? = when {
    streak >= 100 -> "Сто дней подряд" to "Английский стал привычкой. Это лучшее, что можно для него сделать."
    streak >= 30 -> "Месяц без пропусков" to "Интервалы FSRS растут, а повторения занимают всё меньше времени."
    streak >= 7 -> "Неделя без пропусков" to "Короткие ежедневные занятия запоминаются лучше редких длинных."
    else -> null
}

/** Полоса последних семи дней: залитый круг — день с ответами, сегодня обведено. */
@Composable
private fun WeekStrip(week: List<Int>) {
    val content = androidx.compose.material3.LocalContentColor.current
    val names = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    val todayIndex = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEachIndexed { i, count ->
            val dayIndex = (todayIndex - (week.size - 1 - i) + 7 * 3) % 7
            val today = i == week.lastIndex
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // День с ответами — ирис с галочкой, сегодня дополнительно обведено.
                Box(Modifier.size(30.dp)
                    .background(if (count > 0) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                    .then(if (today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall) else Modifier),
                    contentAlignment = Alignment.Center) {
                    if (count > 0) Icon(com.marat.albion.ui.components.AlbionIcons.Check, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.height(6.dp))
                Text(names[dayIndex], style = MaterialTheme.typography.labelSmall,
                    color = if (today) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.6f))
            }
        }
    }
}
