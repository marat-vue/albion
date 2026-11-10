package com.marat.albion.ui.deck

import com.marat.albion.ui.components.DestructiveConfirmationDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import com.marat.albion.ui.theme.appear
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.StatPair
import com.marat.albion.ui.components.StatMetric
import com.marat.albion.ui.components.AdaptivePair
import com.marat.albion.ui.components.metricMinWidth
import com.marat.albion.ui.components.gutter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.marat.albion.ui.components.PaneDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.repo.DeckSummary
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.SectionHeader
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.nav.BrowseRoute
import com.marat.albion.ui.nav.LibraryRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.StudyRoute
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun DeckScreen(deck: Deck, navigator: Navigator) {
    val c = LocalAppContainer.current
    val summaryFlow = remember(deck) { c.study.observeSummaries().map { list -> list.first { it.deck == deck } } }
    val summary by summaryFlow.collectAsStateWithLifecycle(DeckSummary(deck))
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack = navigator::back) {
            Box {
                com.marat.albion.ui.components.RoundIconButton(AlbionIcons.MoreHoriz, "Ещё", { menu = true }, filled = true)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Сбросить прогресс колоды") }, onClick = { menu = false; confirmReset = true },
                        colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error))
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = gutter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp),
        ) deckContent@{
            Column(Modifier) {
                Eyebrow("Колода · %02d".format(deck.ordinal + 1))
                Spacer(Modifier.height(12.dp))
                Text(deck.title, style = if (com.marat.albion.ui.components.LocalNarrow.current) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(deck.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (deck == Deck.BOOK_WORDS && summary.total == 0) {
                Spacer(Modifier.height(24.dp))
                Text("Добавьте первое слово", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                Text("Откройте книгу, коснитесь слова и нажмите «Добавить в “Слова из книг”». " +
                    "Перевод и пример можно поправить перед сохранением.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Открыть библиотеку", onClick = { navigator.switchTab(LibraryRoute) },
                    trailingIcon = AlbionIcons.ArrowForward)
                return@deckContent
            }

            // Главное действие колоды — инверсный блок: что ждёт сегодня и «Начать занятие».
            com.marat.albion.ui.components.BoundVolume(Modifier.fillMaxWidth().padding(top = 24.dp).appear(2)) {
                DeckStudyMetrics(summary.due, summary.newAvailable)
                PrimaryButton(
                    text = if (summary.toStudy > 0) "Начать занятие" else "На сегодня всё",
                    onClick = { navigator.open(StudyRoute(deck)) },
                    enabled = summary.toStudy > 0,
                    trailingIcon = AlbionIcons.ArrowForward,
                    inverse = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            GhostButton(
                "Все карточки и поиск",
                onClick = { navigator.open(BrowseRoute(deck)) },
                icon = AlbionIcons.Search,
                modifier = Modifier.appear(3),
            )

            Column(Modifier) {
                SectionHeader("Прогресс", trailing = "${(summary.progress * 100).toInt()}%")
                Spacer(Modifier.height(8.dp))
                ThinProgress(summary.progress)
                Spacer(Modifier.height(16.dp))
                StatPair(StatMetric(summary.total, "всего"), StatMetric(summary.total - summary.started, "не начато"), Modifier.appear(4))
                Spacer(Modifier.height(8.dp))
                StatPair(StatMetric(summary.learning, "изучаются"), StatMetric(summary.mature, "выучено · 21+ дн"))
            }

            Column(Modifier) {
                SectionHeader("Как это работает")
                Text(
                    "Посмотрите на лицевую сторону и вспомните значение. Переверните карточку и честно оцените ответ — " +
                        "FSRS рассчитает момент, когда вы начнёте забывать, и покажет карточку именно тогда.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        DestructiveConfirmationDialog(
            title = "Сбросить прогресс?",
            message = "Все карточки колоды «${deck.title}» снова станут новыми. Расписание повторений будет сброшено, статистика занятий сохранится. Отменить сброс нельзя.",
            confirmLabel = "Сбросить",
            onConfirm = { confirmReset = false; scope.launch { c.study.resetDeck(deck) } },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
internal fun DeckStudyMetrics(due: Int, newAvailable: Int) {
    val first = StatMetric(due, "к повторению")
    val second = StatMetric(newAvailable, "новых сегодня")
    val minWidth = metricMinWidth(listOf(first, second), MaterialTheme.typography.displayMedium, MaterialTheme.typography.labelMedium)
    val metric: @Composable (StatMetric, Modifier) -> Unit = { item, modifier ->
        Column(modifier) {
            com.marat.albion.ui.theme.AnimatedNumber(item.value!!, MaterialTheme.typography.displayMedium)
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current)
        }
    }
    AdaptivePair(minWidth, spacing = 24.dp, first = { metric(first, it) }, second = { metric(second, it) })
}
