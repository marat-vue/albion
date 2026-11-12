package com.marat.albion.ui.games

import androidx.compose.foundation.shape.CircleShape

import com.marat.albion.ui.theme.quietItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.settings.PracticeOptions
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.GameRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.appear
import androidx.compose.foundation.border

internal val practiceGroups = listOf(
    "Словарный запас" to listOf(Game.TYPE_ANSWER, Game.IRREGULAR),
    "Грамматика" to listOf(Game.GRAMMAR_CHOICE, Game.GRAMMAR_GAP, Game.SENTENCE, Game.TENSES),
    "Речь и слух" to listOf(Game.PRONUNCIATION, Game.SHADOWING),
)

/**
 * Фильтр и каталог — в начале; сохранённое занятие и ошибки раскрываются по запросу.
 */
@Composable
fun GamesScreen(navigator: Navigator) {
    var group by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    val c = LocalAppContainer.current
    val settings = c.settings
    val lastName by settings.lastPractice.collectAsStateWithLifecycle(null)
    val lastGame = Game.entries.firstOrNull { it.name == lastName }
    val featured = lastGame ?: Game.TYPE_ANSWER
    val defaultCount = if (featured == Game.IRREGULAR) 20 else 10
    val options by remember(featured) { settings.practiceOptions(featured.name, defaultCount) }
        .collectAsStateWithLifecycle(PracticeOptions(count = defaultCount))
    val needs by remember { c.study.observePracticeNeeds() }.collectAsStateWithLifecycle(emptyList())

    val pending = needs.mapNotNull { need ->
        val game = Game.entries.firstOrNull { it.name == need.game } ?: return@mapNotNull null
        Triple(game, Deck.byId(need.deck), need.count)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 480.dp
        val largeType = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        val saved: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
            if (lastGame != null || pending.isNotEmpty()) item(key = "saved-practice", span = { GridItemSpan(maxLineSpan) }) {
                Disclosure(if (lastGame != null) "Продолжить" else "Ошибки",
                    if (pending.isNotEmpty() && (lastGame == null || !largeType)) mistakesLabel(pending.sumOf { it.third }) else null,
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (lastGame != null) PracticeContinuation(featured, options, continuing = true) { navigator.open(GameRoute(featured)) }
                    if (pending.isNotEmpty()) {
                        Text("Повторить ошибки", style = MaterialTheme.typography.titleSmall)
                        pending.forEach { (game, deck, count) ->
                            MistakeRow(game, deck, count) { navigator.open(GameRoute(game, deck, onlyMistakes = true)) }
                        }
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 32.dp + com.marat.albion.ui.components.bottomChrome),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader("Практика", compact = true, trailing = {
                    Row {
                        FeatureHelp("practice", "Как заниматься",
                            "Выберите навык, затем словарь или уровень и длину занятия. Параметры каждого упражнения сохраняются.\n\nОшибки сохраняются: сложные слова и задания будут встречаться чаще.\n\nПрактика учитывается в дневной цели и статистике; расписание повторения карточек остаётся прежним.", showOnFirstUse = false)
                        SettingsShortcut(navigator::openSettings)
                    }
                })
            }
            item(key = "practice-filter", span = { GridItemSpan(maxLineSpan) }) {
                OptionPicker("Навык", listOf("Все") + practiceGroups.map { it.first }, group + 1,
                    { group = it - 1 }, Modifier.appear(2))
            }
            if (!short) saved()
            practiceGroups.filterIndexed { i, _ -> group == -1 || group == i }.forEach { (title, games) ->
                if (!short) item(key = "h:$title", span = { GridItemSpan(maxLineSpan) }) {
                    ModuleHeading((practiceGroups.indexOfFirst { it.first == title } + 1).toString().padStart(2, '0'), title,
                        if (largeType) null else games.size.let { "$it " + com.marat.albion.ui.library.pluralRu(it, "упражнение", "упражнения", "упражнений") },
                        quietItem(), compact = largeType)
                }
                items(games, key = { it.name }, span = { GridItemSpan(maxLineSpan) }) { game ->
                    PracticeTile(game, quietItem()) { navigator.open(GameRoute(game)) }
                }
            }
            if (short) saved()
            item(key = "conversation", span = { GridItemSpan(maxLineSpan) }) {
                Panel(Modifier.fillMaxWidth().appear(3), onClick = { navigator.open(com.marat.albion.ui.nav.ConversationsRoute) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(AlbionIcons.Chat, null, Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Разговор с ИИ", style = MaterialTheme.typography.titleMedium)
                            Text("Беседа текстом и голосом с разбором ошибок · офлайн", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(AlbionIcons.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

        }
    }
}

/** Full-width title and action: decoration never squeezes the longest word. */
@Composable
internal fun PracticeContinuation(game: Game, options: PracticeOptions, continuing: Boolean, onOpen: () -> Unit) {
    val scope = if (game.isGrammar) options.level.ifEmpty { "Все уровни" }
        else (game.decks.firstOrNull { it.id == options.deckId } ?: game.decks.first()).title
    val length = if (options.count == Int.MAX_VALUE) "весь словарь" else "${options.count} ${game.unit}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val titleStyle = MaterialTheme.typography.titleLarge
        val wordWidth = with(density) {
            game.title.split(Regex("\\s+")).maxOf { measurer.measure(it, titleStyle, softWrap = false).size.width }.toDp()
        }
        val padding = ((maxWidth - wordWidth) / 2).coerceIn(4.dp, 12.dp)
        BoundVolume(Modifier.fillMaxWidth(), contentPadding = PaddingValues(padding)) {
            Text(if (continuing) "Продолжить" else "Первое занятие", style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current)
            Text(game.title, style = MaterialTheme.typography.titleLarge)
            Text(if (continuing) listOf(scope.takeUnless { it == game.title }, length).filterNotNull().joinToString(" · ")
                else game.tagline, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
            PrimaryButton(if (continuing) "Продолжить" else "Попробовать", onOpen, inverse = true)
        }
    }
}

/** Строка упражнения: иконка слева, справа название и короткое описание. */
@Composable
private fun PracticeTile(game: Game, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Panel(modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(game.icon, null, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleMedium)
                Text(game.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(AlbionIcons.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Строка «повторить ошибки»: число ошибок в квадрате, упражнение и колода, стрелка. */
@Composable
private fun MistakeRow(game: Game, deck: Deck?, count: Int, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text("$count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleMedium)
                Text(if (deck != null && game.decks.size > 1) "${deck.title} · ${mistakesLabel(count)}" else mistakesLabel(count),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AlbionIcons.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun mistakesLabel(n: Int) = "$n " + when {
    n % 100 in 11..14 -> "ошибок"
    n % 10 == 1 -> "ошибка"
    n % 10 in 2..4 -> "ошибки"
    else -> "ошибок"
}
