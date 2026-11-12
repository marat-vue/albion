package com.marat.albion.ui.grammar

import com.marat.albion.ui.theme.selectedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.AppContainer
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.db.GrammarProgressEntity
import com.marat.albion.data.grammar.*
import com.marat.albion.ui.browse.SearchField
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.LocalBesideDetail
import com.marat.albion.ui.nav.LocalPaneHeight
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.TopicRoute
import com.marat.albion.ui.nav.TopicQuizRoute
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GrammarHomeState(
    val loading: Boolean = true,
    val book: GrammarBook = GrammarBook.EMPTY,
    val progress: Map<String, GrammarProgressEntity> = emptyMap(),
    val lastTopic: String? = null,
    val reviews: List<GrammarReview.Item> = emptyList(),
    val quizTopics: Set<Int> = emptySet(),
)

class GrammarViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<GrammarHomeState> = combine(
        flow { emit(c.grammar.book()) },
        c.grammar.observeProgress(),
        c.settings.settings,
        c.grammar.observeQuizTopics(),
    ) { book, progress, s, quizTopics ->
        val byId = progress.associateBy { it.topicId }
        GrammarHomeState(false, book, byId, s.lastGrammarTopic, GrammarReview.scheduled(book, byId, quizTopics.toSet()), quizTopics.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrammarHomeState())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GrammarScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel { GrammarViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    var bookmarksOnly by rememberSaveable { mutableStateOf(false) }
    var reviewsOnly by rememberSaveable { mutableStateOf(false) }
    var reviewDetails by rememberSaveable { mutableStateOf(false) }
    var references by rememberSaveable { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val shortScreen = LocalPaneHeight.current?.let { it < 480.dp }
        ?: (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480)
    val routeState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val book = state.book
    val now = grammarReviewTime()
    val stages = remember(book) { grammarStages(book) }
    val nextStep = GrammarNextStep.recommend(book, state.progress, state.quizTopics, now, lastId = state.lastTopic)
    val recommendation = nextStep?.topic
    val activeStage = stages.firstOrNull { it.level == level }
        ?: stages.firstOrNull { it.level == recommendation?.level } ?: stages.firstOrNull()
    val due = state.reviews.takeWhile { it.dueAt <= now }
    val filtering = query.isNotBlank() || bookmarksOnly || reviewsOnly
    val listState = if (filtering) searchState else routeState
    val showNext = !LocalBesideDetail.current && !filtering && nextStep != null && recommendation?.level == activeStage?.level
    // Урок из «Следующего шага» остаётся в маршруте и подсвечен: нумерация не прерывается.
    val route = activeStage?.topics.orEmpty()
    val q = query.trim().lowercase()
    val found = if (!filtering) emptyList() else (if (reviewsOnly) due.map { it.topic } else book.topics).filter { topic ->
        (topic.kind == TopicKind.Lesson || topic.kind == TopicKind.Reference) && GrammarSearch.matches(topic, q) &&
            (!bookmarksOnly || state.progress[topic.id]?.bookmarked == true)
    }
    val open: (Topic) -> Unit = { navigator.openDetail(TopicRoute(it.id)) }
    val quiz: (Topic) -> Unit = { topic -> topic.number?.let { navigator.open(TopicQuizRoute(it, topic.id, topic.title)) } }
    val shownTopic = (navigator.shownDetail as? TopicRoute)?.topicId
    val besideDetail = LocalBesideDetail.current
    LaunchedEffect(shownTopic, besideDetail, book) {
        if (besideDetail) book.topic(shownTopic.orEmpty())?.takeIf { it.kind == TopicKind.Lesson }?.let { level = it.level }
    }
    LaunchedEffect(shownTopic, besideDetail, filtering, activeStage?.level) {
        if (!besideDetail || shownTopic == null) return@LaunchedEffect
        val key = if (filtering) "f-$shownTopic" else "route-$shownTopic"
        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) return@LaunchedEffect
        val index = (if (filtering) found else route).indexOfFirst { it.id == shownTopic }
        if (index >= 0) listState.requestScrollToItem(index + 1 + (if (shortScreen) 1 else 0) + if (filtering) 1 else 2 + (if (showNext) 1 else 0) + (if (state.reviews.isNotEmpty()) 1 else 0))
    }
    val search: @Composable () -> Unit = {
        SearchField(query, { query = it }, "Найти правило или пример…", Modifier.padding(bottom = 16.dp))
    }
    // Выбор раздела прокручивается вместе со списком: закреплённая шапка занимала почти половину экрана.
    val viewPicker: @Composable () -> Unit = {
        GrammarViewPicker(if (bookmarksOnly) 1 else if (reviewsOnly) 2 else 0, due.size) { selected ->
            when (selected) {
                0 -> { query = ""; bookmarksOnly = false; reviewsOnly = false }
                1 -> { bookmarksOnly = true; reviewsOnly = false }
                2 -> { reviewsOnly = true; bookmarksOnly = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter)) {
            AdaptiveScreenTitle("Учебник", Modifier.heightIn(min = 56.dp)) {
                if (!largeText) {
                    Row {
                        com.marat.albion.ui.components.RoundIconButton(AlbionIcons.Bookmarks, "Справочники", { references = true }, filled = true)
                        SettingsShortcut(navigator::openSettings)
                    }
                } else Box {
                    Row {
                        // При крупном шрифте поле поиска занимало треть экрана — теперь оно открывается лупой.
                        com.marat.albion.ui.components.RoundIconButton(AlbionIcons.Search, if (searchOpen) "Скрыть поиск" else "Поиск",
                            { searchOpen = !searchOpen; if (!searchOpen) query = "" }, filled = true)
                        Spacer(Modifier.width(8.dp))
                        com.marat.albion.ui.components.RoundIconButton(AlbionIcons.MoreHoriz, "Меню учебника", { headerMenu = true }, filled = true)
                    }
                    com.marat.albion.ui.components.PaneDropdownMenu(headerMenu, { headerMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Справочники и курс") }, onClick = { headerMenu = false; references = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Настройки") }, onClick = { headerMenu = false; navigator.openSettings() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!shortScreen && (!largeText || searchOpen || query.isNotBlank())) search()
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp + com.marat.albion.ui.components.bottomChrome)) {
            if (shortScreen) item(key = "search-controls") { search() }
            item(key = "view-picker") { viewPicker() }
            if (state.loading) { item { com.marat.albion.ui.components.LoadingState("Открываем учебник") }; return@LazyColumn }
            if (book.lessons.isEmpty()) {
                item { EmptyState("Учебник недоступен", "Проверьте учебные материалы в настройках.")
                    GhostButton("Открыть настройки", navigator::openSettings) }
                return@LazyColumn
            }
            if (filtering) {
                item { Text(if (reviewsOnly) "К повторению: ${found.size}" else if (bookmarksOnly) "Сохранено: ${found.size}" else "Найдено: ${found.size}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
                items(found, key = { "f-${it.id}" }) { topic ->
                    GrammarSearchResult(topic, state.progress[topic.id], q, now, topic.id == shownTopic, open)
                    if (reviewsOnly && topic.number in state.quizTopics) GhostButton("Повторить тему ${topic.number}", { quiz(topic) })
                }
                if (found.isEmpty()) item {
                    val neverScheduled = state.reviews.isEmpty()
                    EmptyState(if (q.isNotBlank()) "Темы не найдены" else if (reviewsOnly) { if (neverScheduled) "Пока нет тем для повторения" else "Все повторения выполнены" }
                        else if (bookmarksOnly) "Пока нет закладок" else "Темы не найдены",
                        if (q.isNotBlank()) "Измените запрос или вернитесь к маршруту." else if (reviewsOnly && neverScheduled) "Прочитайте урок и пройдите проверку — тема получит дату повторения."
                        else if (reviewsOnly) "Новые темы появятся по плану повторения."
                        else if (bookmarksOnly) "Сохраните нужное правило кнопкой закладки в уроке." else "Попробуйте другое слово или пример.")
                    GhostButton("Вернуться к маршруту", { query = ""; bookmarksOnly = false; reviewsOnly = false })
                }
            } else {
                item(key = "atlas") { GrammarAtlas(stages, activeStage?.level, state.progress, book.lessons.size,
                    book.lessons.count { state.progress[it.id]?.masteredAt != null }) { level = it } }
                if (showNext && nextStep != null) item(key = "next") { GrammarNextCard(nextStep,
                    onClick = { if (nextStep.kind == GrammarNextStep.Kind.Read) open(nextStep.topic) else quiz(nextStep.topic) },
                    onRead = { open(nextStep.topic) }) }
                if (state.reviews.isNotEmpty()) item(key = "review") { GrammarReviewCard(state.reviews, due, now, reviewDetails,
                    { reviewDetails = !reviewDetails }, open, { reviewsOnly = true }) }
                activeStage?.let { stage ->
                    item(key = "stage-${stage.level}") { GrammarStageHeading(stage, stages.indexOf(stage),
                        stage.topics.count { state.progress[it.id]?.masteredAt != null }, stage.topics.count { state.progress[it.id]?.readAt != null }) }
                    itemsIndexed(route, key = { _, topic -> "route-${topic.id}" }) { index, topic ->
                        GrammarRouteLesson(topic, state.progress[topic.id], topic.id == recommendation?.id, topic.id == shownTopic,
                            index == 0, index == route.lastIndex, null, now, onClick = { open(topic) },
                            modifier = Modifier.sharedElement("topic:${topic.id}"))
                    }
                    stages.getOrNull(stages.indexOf(stage) + 1)?.let { next -> item {
                        GhostButton("Следующий этап · ${next.level}", {
                            level = next.level
                            scope.launch { if (reduced) routeState.scrollToItem(0) else routeState.animateScrollToItem(0) }
                        })
                    } }
                }
            }
        }
    }
    if (references) com.marat.albion.ui.components.PaneBottomSheet(
        onDismissRequest = { references = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = gutter)) {
            com.marat.albion.ui.components.SheetHeader("Справочники и курс", { references = false })
            book.topics.filter { it.kind != TopicKind.Lesson }.forEach { topic ->
                com.marat.albion.ui.components.EditorialLink(topic.title, if (topic.kind == TopicKind.Reference) "Справочник" else "О курсе",
                    { references = false; open(topic) })
            }
        }
    }
}

@Composable
private fun GrammarReviewCard(
    reviews: List<GrammarReview.Item>, due: List<GrammarReview.Item>, now: Long, expanded: Boolean,
    onExpand: () -> Unit, open: (Topic) -> Unit, onQueue: () -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        SectionHeader("Повторение грамматики", trailing = due.size.toString())
        Panel(contentPadding = PaddingValues(16.dp)) {
            if (due.isNotEmpty()) {
                Text("${due.size} ${com.marat.albion.ui.library.pluralRu(due.size, "тема", "темы", "тем")} ждут повторения.",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { open(due.first().topic) }, modifier = Modifier.weight(1f)) { Text("Открыть правило", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = onQueue, modifier = Modifier.weight(1f)) { Text("Очередь (${due.size})", color = MaterialTheme.colorScheme.onSurface) }
                }
            } else {
                val next = reviews.first()
                Text("Следующее повторение: ${grammarReviewDate(next.dueAt, now)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(next.topic.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { open(next.topic) }) { Text("Открыть тему", color = MaterialTheme.colorScheme.primary) }
            }
            TextButton(onClick = onExpand, modifier = Modifier.semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }) {
                Text("Как работает повторение", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                DisclosureIndicator(expanded, Modifier.padding(start = 12.dp))
            }
            AnimatedVisibility(expanded) {
                Text("Сначала повторяются темы с самой ранней датой. Полный тест обновляет план. " +
                    "Интервалы после успешных тестов: 1, 3, 7, 14, 30 и 60 дней. Менее 70% — повтор через день. " +
                    "Досрочный тест и повтор ошибок не увеличивают интервал.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GrammarSearchResult(
    topic: Topic, progress: GrammarProgressEntity?, query: String, now: Long, selected: Boolean, open: (Topic) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectedRow(selected).clickable { open(topic) }.padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(topic.number?.toString() ?: "§", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(com.marat.albion.ui.components.highlightedQuery(topic.title, query), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                val status = when { progress?.masteredAt != null -> "Освоено"; progress?.readAt != null -> "Прочитано"; else -> null }
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
                GrammarReview.plan(progress)?.let { Text("Повторение: ${grammarReviewDate(it.dueAt, now)}", style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(8.dp))
            if (progress?.bookmarked == true) Icon(AlbionIcons.Bookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            LevelBadge(topic.level)
        }
        if (query.isNotBlank()) Text(com.marat.albion.ui.components.highlightedQuery(GrammarSearch.snippet(topic, query), query), style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp, top = 7.dp))
        Hairline(Modifier.padding(top = 14.dp))
    }
}

