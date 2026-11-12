package com.marat.albion.ui.home

import com.marat.albion.ui.components.QuietCircularProgressIndicator as CircularProgressIndicator

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.SegmentedPicker
import com.marat.albion.ui.theme.appear
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.AppContainer
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.ImportStatus
import com.marat.albion.data.grammar.GrammarBook
import com.marat.albion.data.grammar.Topic
import com.marat.albion.data.grammar.GrammarNextStep
import com.marat.albion.data.repo.StudyOverview
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomeState(
    val study: StudyOverview = StudyOverview(),
    val streak: Int = 0,
    val answersToday: Int = 0,
    val grammar: GrammarBook = GrammarBook.EMPTY,
    val continueTopic: Topic? = null,
    val dailyGoal: Int = 30,
    val sessionSize: Int = 10,
    val grammarStep: GrammarNextStep.Step? = null,
    val loaded: Boolean = false,
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    private val settings = c.settings
    fun setSessionSize(count: Int) { viewModelScope.launch { settings.setShortSessionAnswers(count) } }

    val importStatus: StateFlow<ImportStatus> = c.importer.status

    val state: StateFlow<HomeState> = combine(
        c.study.observeOverview(),
        c.study.observeStats().map { it.streak to it.answersToday },
        flow { emit(c.grammar.book()) },
        c.grammar.observeProgress(),
        combine(c.settings.settings, c.grammar.observeQuizTopics()) { s, q -> s to q.toSet() },
    ) { study, (streak, today), book, progress, (s, quizTopics) ->
        val step = GrammarNextStep.recommend(book, progress.associateBy { it.topicId }, quizTopics, System.currentTimeMillis(), lastId = s.lastGrammarTopic)
        HomeState(study, streak, today, book, step?.topic, s.dailyGoal,
            sessionSize = s.shortSessionAnswers, grammarStep = step, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel { HomeViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.importStatus.collectAsStateWithLifecycle()
    var sessionSize by rememberSaveable { mutableIntStateOf(state.sessionSize) }
    LaunchedEffect(state.sessionSize) { sessionSize = state.sessionSize }
    val date = SimpleDateFormat("d MMMM · EEEE", Locale.forLanguageTag("ru")).format(Date())
    var creating by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compact = maxWidth < 360.dp || maxHeight < 640.dp || LocalDensity.current.fontScale > 1.2f
        val short = maxHeight < 480.dp
        val overview: LazyListScope.() -> Unit = {
            item {
                HomeHero(state, date, navigator::openSettings,
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).appear(0))
            }
            item {
                when (val s = status) {
                    is ImportStatus.Importing -> Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Готовим материалы · ${s.index}/${s.total}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is ImportStatus.Failed -> Column {
                        Text("Не удалось подготовить материалы.", color = MaterialTheme.colorScheme.error)
                        GhostButton("Открыть настройки", navigator::openSettings)
                    }
                    ImportStatus.Empty -> EmptyState("Материалы недоступны", "Откройте настройки, чтобы проверить учебные материалы.")
                    else -> Unit
                }
            }
            item {
                TodayCard(state, sessionSize, Modifier.appear(1), compact, short, status is ImportStatus.Checking || status is ImportStatus.Importing,
                    onSize = { sessionSize = it; vm.setSessionSize(it) },
                    onStart = { navigator.open(StudyRoute(answerLimit = minOf(sessionSize, state.study.today.toStudy).coerceAtLeast(1))) },
                    onRead = { navigator.switchTab(LibraryRoute) })
            }
            item {
                ModuleHeading("02", "План на сегодня", modifier = Modifier.appear(3))
            }
            item {
                val topic = state.continueTopic
                val step = state.grammarStep
                PlanRow(
                    AlbionIcons.MenuBook,
                    title = topic?.title ?: "Открыть учебник",
                    subtitle = topic?.let { listOfNotNull(step?.label, it.number?.let { n -> "Урок $n" }, it.level).joinToString(" · ") }
                        ?: "Короткие уроки и проверка знаний",
                    done = false,
                    modifier = Modifier.padding(bottom = 10.dp).appear(4),
                ) {
                    if (topic != null && step != null && step.kind != GrammarNextStep.Kind.Read && topic.number != null)
                        navigator.open(TopicQuizRoute(topic.number, topic.id, topic.title))
                    else if (topic != null) navigator.open(TopicRoute(topic.id)) else navigator.switchTab(GrammarRoute)
                }
            }
            item { Box(Modifier.padding(bottom = 10.dp).appear(5)) { ReadingBlock(navigator) } }
            item { Box(Modifier.appear(6)) { PracticeBlock(navigator) } }
        }
        val decks: LazyListScope.() -> Unit = {
            val collections = state.study.collections
            item {
                ModuleHeading("03", "Мои коллекции", if (state.loaded && collections.isNotEmpty()) "${collections.size} " +
                    com.marat.albion.ui.library.pluralRu(collections.size, "коллекция", "коллекции", "коллекций") else null)
            }
            if (state.loaded && collections.isEmpty()) item {
                Text("Соберите свои наборы слов из словарей ниже и тренируйтесь по ним. Новые слова коллекций приходят в занятие на сегодня.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            items(collections, key = { "collection:${it.collection.id}" }) { summary ->
                CollectionRow(summary, Modifier.padding(bottom = 8.dp).appear(7)) { navigator.open(CollectionRoute(summary.collection.id)) }
            }
            item {
                GhostButton("Новая коллекция", { creating = true }, icon = AlbionIcons.Add, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                ModuleHeading("04", "Словари", if (state.loaded) "${state.study.dictionaries.size} " +
                    com.marat.albion.ui.library.pluralRu(state.study.dictionaries.size, "словарь", "словаря", "словарей") else null)
            }
            items(state.study.dictionaries, key = { "dictionary:${it.deck.id}" }) { summary ->
                DeckRow(summary, Modifier.padding(bottom = 8.dp).appear(8 + summary.deck.ordinal)) { navigator.open(DictionaryRoute(summary.deck)) }
            }
        }
        val padding = PaddingValues(start = gutter, end = gutter, bottom = 28.dp)
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(Modifier.weight(1f), contentPadding = padding) { overview() }
            LazyColumn(Modifier.weight(1f), contentPadding = padding) {
                item { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(24.dp)) }
                decks()
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) { overview(); decks() }
    }
    if (creating) com.marat.albion.ui.collections.CollectionNameDialog("Новая коллекция", "Создать", { creating = false }) { name ->
        val id = c.collections.create(name)
        creating = false
        navigator.open(CollectionRoute(id))
    }
}

@Composable
internal fun TodayCard(state: HomeState, sessionSize: Int, modifier: Modifier, compact: Boolean, short: Boolean,
                      preparing: Boolean, onSize: (Int) -> Unit, onStart: () -> Unit, onRead: () -> Unit) {
    val available = state.study.today.toStudy
    val planned = minOf(sessionSize, available)
    val loaded = state.loaded && !preparing
    val sizes = listOf(5, 10, 20)
    BoundVolume(modifier.fillMaxWidth(), contentPadding = PaddingValues(if (short) 12.dp else if (compact) 16.dp else 22.dp),
        verticalArrangement = Arrangement.Top) {
        Text(if (!loaded) "Готовим занятие" else if (available > 0) "Занятие на сегодня" else "На сегодня всё",
            style = if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        // The main action precedes descriptions and optional session settings, even in a short window.
        if (!loaded || available > 0) {
            PrimaryButton(if (!loaded) "Готовим занятие…" else "Начать", onClick = onStart,
                enabled = loaded && planned > 0, inverse = true)
        } else PrimaryButton("Открыть библиотеку", onRead, inverse = true)
        Spacer(Modifier.height(8.dp))
        Text(when {
            !loaded -> "Собираем ваши карточки"
            available > 0 -> "${answerLabel(planned)} за занятие · $available ${com.marat.albion.ui.library.pluralRu(available, "карточка доступна", "карточки доступны", "карточек доступно")}"
            state.study.collections.isEmpty() && state.study.today.due == 0 ->
                "Новые слова приходят из ваших коллекций. Создайте коллекцию или тренируйте словарь целиком."
            else -> "Все карточки на сегодня повторены. Время для книги."
        }, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current)
        if (loaded) state.study.today.newNote?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current)
        }
        if (!loaded || available > 0) {
            Disclosure("Объём занятия", "${answerLabel(sessionSize)}") {
                SegmentedPicker(sizes.map { "$it" }, sizes.indexOf(sessionSize).coerceAtLeast(0), { onSize(sizes[it]) }, inverse = true)
            }
        }
    }
}

private fun answerLabel(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "ответов"
    count % 10 == 1 -> "ответ"
    count % 10 in 2..4 -> "ответа"
    else -> "ответов"
}

@Composable
private fun ReadingBlock(navigator: Navigator) {
    val c = LocalAppContainer.current
    val books by remember { c.library.observeLibrary() }.collectAsStateWithLifecycle(emptyList())
    val current = books.firstOrNull { it.lastRead != null && it.progress < 0.999f }
    PlanRow(
        AlbionIcons.AutoStories,
        title = current?.book?.title ?: "Открыть библиотеку",
        subtitle = if (current != null) "Продолжить чтение · ${(current.progress * 100).toInt()}%" else "Книги с переводом касанием",
        done = false,
        progress = current?.progress,
    ) { if (current != null) navigator.open(ReaderRoute(current.book.id)) else navigator.switchTab(LibraryRoute) }
}

/** Практика в плане дня: если накопились ошибки — повторить их, иначе открыть каталог. */
@Composable
private fun PracticeBlock(navigator: Navigator) {
    val c = LocalAppContainer.current
    val needs by remember { c.study.observePracticeNeeds() }.collectAsStateWithLifecycle(emptyList())
    val top = needs.maxByOrNull { it.count }
    val game = top?.let { n -> com.marat.albion.ui.games.Game.entries.firstOrNull { it.name == n.game } }
    PlanRow(
        AlbionIcons.EditNote,
        title = if (game != null) "Повторить ошибки" else "Практика",
        subtitle = if (game != null) "${game.title} · ${top.count}" else "Упражнения на слова, грамматику и речь",
        done = false,
    ) {
        if (game != null) navigator.open(com.marat.albion.ui.nav.GameRoute(game, com.marat.albion.data.content.Deck.byId(top.deck), onlyMistakes = true))
        else navigator.switchTab(com.marat.albion.ui.nav.GamesRoute)
    }
}
