package com.marat.albion.ui.browse

import com.marat.albion.ui.theme.sharedElement
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.theme.selectedContent
import com.marat.albion.ui.theme.quietItem
import com.marat.albion.ui.theme.IpaStyle
import com.marat.albion.ui.theme.controlBorder
import androidx.compose.ui.focus.onFocusChanged
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.components.OptionPicker
import com.marat.albion.ui.components.selectedRow
import com.marat.albion.ui.theme.quietFloatAsState as animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.marat.albion.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.repo.toMemory
import com.marat.albion.ui.components.AudioButton
import com.marat.albion.ui.components.EmptyState
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.Hairline
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.SectionHeader
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.WordMeanings
import com.marat.albion.ui.components.lerpColor
import com.marat.albion.ui.nav.CardRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.study.CardBack
import com.marat.albion.ui.study.rememberCardAudio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val filters = listOf("Все", "Не начаты", "Учу", "На повторении", "Пора повторить")
private val cardLevels = listOf("", "A1", "A2", "B1", "B2", "C1")

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(deck: Deck, navigator: Navigator, collectionId: Long? = null) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    // Подбор слов в коллекцию: касание строки добавляет слово или убирает его.
    val target by remember(collectionId) {
        collectionId?.let { id -> c.collections.observe(id) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val picked by remember(collectionId) {
        collectionId?.let { id -> c.collections.observeWords(id) } ?: kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsStateWithLifecycle(emptySet())
    val isPicked = { card: CardEntity -> com.marat.albion.data.repo.CollectionRepository.wordOf(card) in picked }
    val toggle = { card: CardEntity ->
        if (collectionId != null) scope.launch { runCatching { c.collections.setContains(collectionId, card, !isPicked(card)) } }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var level by rememberSaveable { mutableStateOf("") }
    var detailed by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val queryFlow = remember { MutableStateFlow(query) }
    val filterFlow = remember { MutableStateFlow(filter) }
    LaunchedEffect(query) { queryFlow.value = query }
    LaunchedEffect(filter) { filterFlow.value = filter }
    val results = remember(deck) {
        combine(queryFlow.debounce(150), filterFlow) { q, f -> q to f }.flatMapLatest { (q, f) -> c.study.browse(deck, q, f) }
    }
    val cards by remember(results) { results.map { it as List<CardEntity>? } }.collectAsStateWithLifecycle(null)
    val audio = rememberCardAudio(c.audio)
    val showRussian by remember { c.settings.settings.map { it.browseShowRussian } }.collectAsStateWithLifecycle(true)
    val activeFilters = (if (filter != 0) 1 else 0) + (if (level.isNotEmpty()) 1 else 0)
    val openFilters = {
        focus.clearFocus()
        keyboard?.hide()
        filtersOpen = true
    }

    val shownCards = cards?.filter { level.isEmpty() || it.level == level }
    val cardList: @Composable (Modifier, Boolean) -> Unit = { modifier, showCount ->
        if (shownCards == null) Box(modifier) { com.marat.albion.ui.components.LoadingState("Ищем слова") }
        else if (shownCards.isEmpty()) {
            Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                EmptyState("Ничего не найдено", "Измените запрос или фильтр.")
            }
        } else {
            val shownCard = (navigator.shownDetail as? CardRoute)?.cardId
            LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp + com.marat.albion.ui.components.bottomChrome)) {
                if (showCount) item {
                    Eyebrow(if (level.isEmpty()) "${shownCards.size} карточ." else "${shownCards.size} карточ. · $level",
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                if (showCount && collectionId != null) item {
                    val missing = shownCards.filterNot(isPicked)
                    if (missing.isNotEmpty()) TextButton(onClick = {
                        scope.launch { runCatching { c.collections.add(collectionId, missing) } }
                    }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("Добавить все найденные · ${missing.size}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(shownCards, key = { it.id }) { card ->
                    CardRow(
                        card = card,
                        showRussian = showRussian, detailed = detailed, query = query,
                        playing = audio.playingKey == "term:${card.id}",
                        selected = card.id == shownCard,
                        onPlay = { audio.play(deck, card.audio, card.term, "term:${card.id}") },
                        onClick = { if (collectionId != null) toggle(card) else navigator.openDetail(CardRoute(card.id)) },
                        picked = if (collectionId != null) isPicked(card) else null,
                        modifier = quietItem().sharedElement("card:${card.id}"),
                    )
                }
            }
        }
    }
    val searchBar: @Composable (Modifier, Boolean) -> Unit = { modifier, showViewToggle ->
        Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.marat.albion.ui.components.RoundIconButton(AlbionIcons.ArrowBack, "Назад", navigator::back, filled = true)
            SearchField(query, { query = it }, "Слово или перевод", Modifier.weight(1f).padding(horizontal = 8.dp))
            BrowseFilterButton((if (filter != 0) 1 else 0) + (if (level.isNotEmpty()) 1 else 0), openFilters)
            if (showViewToggle) com.marat.albion.ui.components.RoundIconButton(if (detailed) AlbionIcons.List else AlbionIcons.Menu,
                if (detailed) "Кратко" else "Подробно", { detailed = !detailed }, filled = true)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        val compactFilters = WindowInsets.isImeVisible || maxHeight < 600.dp || LocalDensity.current.fontScale > 1.3f
        val shortWindow = maxWidth >= 560.dp && maxHeight < 360.dp
        if (shortWindow) {
            val split = WindowInsets.isImeVisible && maxHeight < 240.dp
            CompactBrowseWorkspace(split, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                search = { searchBar(Modifier, !split) },
                results = { cardList(Modifier.fillMaxSize(), !split) })
        } else Column(Modifier.fillMaxSize()) {
            TopBar(onBack = navigator::back, title = target?.let { "${deck.title} → ${it.name}" } ?: deck.title)
            if (collectionId != null) Text("Коснитесь слова, чтобы добавить его в коллекцию или убрать",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = gutter).padding(bottom = 8.dp))
            SearchField(query, { query = it }, "Слово или перевод", Modifier.padding(horizontal = gutter))
            if (compactFilters) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = gutter).padding(top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BrowseFilterButton(activeFilters, openFilters, Modifier.weight(1f))
                    FieldIconButton(if (detailed) AlbionIcons.List else AlbionIcons.Menu,
                        if (detailed) "Кратко" else "Подробно") { detailed = !detailed }
                }
            } else {
                BrowseFilterFields(filter, level, { filter = it }, { level = it },
                    Modifier.padding(horizontal = gutter).padding(top = 12.dp, bottom = 8.dp)) {
                    FieldIconButton(if (detailed) AlbionIcons.List else AlbionIcons.Menu,
                        if (detailed) "Кратко" else "Подробно") { detailed = !detailed }
                }
            }
            cardList(Modifier.weight(1f), true)
        }
    }
    if (filtersOpen) ScrollableSheet("Фильтры карточек", { filtersOpen = false }) {
        BrowseFilterFields(filter, level, { filter = it }, { level = it })
        if (filter != 0 || level.isNotEmpty()) TextButton(onClick = { filter = 0; level = "" }) {
            Text("Сбросить фильтры")
        }
        PrimaryButton("Показать карточки", { filtersOpen = false })
    }
}

/** Reposition the same input node, preserving focus and the IME as the usable height changes. */
@Composable
private fun CompactBrowseWorkspace(
    split: Boolean,
    modifier: Modifier,
    search: @Composable () -> Unit,
    results: @Composable () -> Unit,
) {
    Layout(content = { search(); results() }, modifier = modifier) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val searchWidth = if (split) width / 2 else width
        val searchArea = children[0].measure(Constraints(minWidth = searchWidth, maxWidth = searchWidth, maxHeight = height))
        val resultsArea = children[1].measure(Constraints.fixed(
            width = if (split) width - searchWidth else width,
            height = if (split) height else (height - searchArea.height).coerceAtLeast(0),
        ))
        layout(width, height) {
            searchArea.placeRelative(0, 0)
            resultsArea.placeRelative(if (split) searchWidth else 0, if (split) 0 else searchArea.height)
        }
    }
}

@Composable
private fun BrowseFilterButton(active: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier) { Text(if (active == 0) "Фильтры" else "Фильтры · $active") }
}

@Composable
private fun BrowseFilterFields(
    filter: Int,
    level: String,
    onFilter: (Int) -> Unit,
    onLevel: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionPicker("Статус карточек", filters, filter, onFilter)
        // Кнопка справа — квадрат на всю высоту поля уровня.
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPicker("Уровень", cardLevels.map { it.ifEmpty { "Все уровни" } }, cardLevels.indexOf(level),
                { onLevel(cardLevels[it]) }, Modifier.weight(1f))
            trailing()
        }
    }
}

/** Квадратная кнопка рядом с полем выбора: та же поверхность и рамка, высота поля. */
@Composable
private fun FieldIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val feedback = com.marat.albion.ui.theme.LocalFeedback.current
    Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).heightIn(min = 48.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.controlBorder)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = description) { feedback.selection(); onClick() }
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp))
    }
}

/** Строгое поле поиска: только нижняя линия, которая светлеет при вводе. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val active by animateFloatAsState(if (value.isNotEmpty()) 1f else 0f, label = "search")
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.controlBorder, MaterialTheme.shapes.medium)
            .padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AlbionIcons.Search, null, tint = lerpColor(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, active), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.semantics { contentDescription = placeholder }.onFocusChanged { focused = it.isFocused }.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(AlbionIcons.Clear, "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CardRow(card: CardEntity, showRussian: Boolean, detailed: Boolean, query: String, playing: Boolean, selected: Boolean, onPlay: () -> Unit, onClick: () -> Unit, modifier: Modifier, picked: Boolean? = null) {
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    val clickable = if (picked == null) Modifier.clickable(onClick = onClick)
        else Modifier.toggleable(picked, role = androidx.compose.ui.semantics.Role.Checkbox) { onClick() }
    val highlighted = selected || picked == true
    Column(modifier.fillMaxWidth().selectedRow(highlighted).then(clickable)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            com.marat.albion.ui.components.highlightedQuery(card.term, query),
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (highlighted) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        LevelBadge(card.level)
                    }
                    Spacer(Modifier.height(2.dp))
                    if (card.ipa.isNotBlank()) Text(card.ipa, style = IpaStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize, lineHeight = MaterialTheme.typography.bodySmall.lineHeight), color = if (highlighted) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                AudioButton(playing = playing, onClick = onPlay, size = 48.dp, primary = false)
                if (picked != null) Box(Modifier.padding(start = 8.dp).size(48.dp), contentAlignment = Alignment.Center) {
                    val mark = if (picked) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.controlBorder
                    Box(Modifier.size(22.dp).border(1.5.dp, mark, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                        if (picked) Icon(AlbionIcons.Check, null, Modifier.size(18.dp), tint = mark)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val senses = com.marat.albion.data.repo.WordCards.members(card)
            if (detailed || expanded) WordMeanings(card, showRussian)
            else {
                val match = senses.firstOrNull { (if (showRussian) it.meaningRu else it.meaningEn).contains(query, true) } ?: senses.first()
                Text(com.marat.albion.ui.components.highlightedQuery(if (showRussian) match.meaningRu.ifBlank { match.meaningEn } else match.meaningEn, query),
                    style = MaterialTheme.typography.bodyMedium, color = if (highlighted) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!showRussian && query.isNotBlank()) senses.firstOrNull { it.meaningRu.contains(query, true) }?.let { matched ->
                Text(com.marat.albion.ui.components.highlightedQuery("В переводе: ${matched.meaningRu}", query), style = MaterialTheme.typography.bodySmall, color = if (highlighted) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (senses.size > 1 && !detailed) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть значения" else "Все значения · ${senses.size}", color = MaterialTheme.colorScheme.primary)
            }
        }
        Hairline(Modifier.padding(horizontal = gutter))
    }
}

@Composable
fun CardDetailScreen(cardId: String, navigator: Navigator) {
    val c = LocalAppContainer.current
    val cardResult by remember(cardId) { c.study.observeCard(cardId).map { true to it } }.collectAsStateWithLifecycle(false to null)
    val card = cardResult.second
    val state by remember(cardId) { c.study.observeState(cardId) }.collectAsStateWithLifecycle(null)
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.marat.albion.data.settings.Settings())
    val audio = rememberCardAudio(c.audio)
    val scope = rememberCoroutineScope()
    var editCard by remember { mutableStateOf<CardEntity?>(null) }
    var confirmRemove by rememberSaveable(cardId) { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack = navigator::back)
        val current = card ?: run {
            if (!cardResult.first) com.marat.albion.ui.components.LoadingState("Открываем карточку")
            else { EmptyState("Карточка недоступна", "Она могла быть удалена. Вернитесь к списку и выберите другую."); TextButton(onClick = navigator::back) { Text("К списку") } }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp + com.marat.albion.ui.components.bottomChrome),
        ) {
            Panel(
                Modifier.padding(top = 20.dp),
                contentPadding = PaddingValues(0.dp), color = MaterialTheme.colorScheme.surfaceContainer, borderColor = MaterialTheme.colorScheme.outline,
            ) {
                CardBack(current, c.study.irregular(current), audio, showRussian = settings.showRussian)
            }
            CardCollections(current)
            SectionHeader("Повторение", Modifier.padding(horizontal = 4.dp))
            Column(Modifier.padding(horizontal = 4.dp)) {
                val s = state
                if (s == null) {
                    Text("Карточка ещё не изучалась.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val r = Fsrs(w = Fsrs.decodeParams(settings.fsrsParams) ?: Fsrs.DEFAULT_W).retrievability(s.toMemory(), System.currentTimeMillis())
                    InfoRow("Статус", stateName(s.state))
                    InfoRow("Следующий показ", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, java.util.Locale.forLanguageTag("ru")).format(Date(s.due)))
                    com.marat.albion.ui.components.Disclosure("Подробности памяти") {
                        InfoRow("Стабильность", Fsrs.formatInterval((s.stability * Fsrs.DAY).toLong()))
                        InfoRow("Сложность", "%.1f / 10".format(s.difficulty))
                        InfoRow("Вероятность вспомнить", "${(r * 100).toInt()}%")
                        InfoRow("Повторений · забываний", "${s.reps} · ${s.lapses}")
                    }
                    Spacer(Modifier.height(18.dp))
                    GhostButton("Сделать новой", onClick = { scope.launch { c.study.resetCard(cardId) } })
                }
                if (current.deck == Deck.BOOK_WORDS.id) {
                    Spacer(Modifier.height(18.dp))
                    com.marat.albion.data.repo.WordCards.members(current).forEachIndexed { index, sense ->
                        TextButton(onClick = { editCard = sense }) { Text(if (current.senses.size > 1) "Изменить значение ${index + 1}" else "Изменить перевод и пример") }
                    }
                    TextButton(onClick = { removeError = false; confirmRemove = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Удалить из «Слова из книг»")
                    }
                }
            }
        }
    }
    editCard?.let { editing ->
        val source = com.marat.albion.data.library.BookWordsRepository.source(editing)
            ?: com.marat.albion.data.library.BookWordSource("", "Личные слова", "")
        com.marat.albion.ui.library.BookWordEditor(editing.term, listOf(editing.meaningRu), editing, source, editing.example1,
            onDismiss = { editCard = null }, onSaved = { editCard = null }, existingCard = editing)
    }
    if (confirmRemove) {
        DestructiveConfirmationDialog(
            title = "Удалить слово?",
            message = "Карточка «${card?.term.orEmpty()}» и история её повторений будут удалены. Отменить удаление нельзя.",
            busy = removing,
            errorMessage = if (removeError) "Не удалось удалить слово. Попробуйте ещё раз." else null,
            onConfirm = {
                removing = true
                removeError = false
                scope.launch {
                    try {
                        c.bookWords.remove(cardId)
                        navigator.back()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        removeError = true
                    } finally {
                        removing = false
                    }
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** В каких коллекциях слово и кнопка, чтобы добавить его в коллекцию или убрать. */
@Composable
private fun CardCollections(card: CardEntity) {
    val c = LocalAppContainer.current
    val all by remember { c.collections.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val containing by remember(card.id) { c.collections.observeContaining(card) }.collectAsStateWithLifecycle(emptySet())
    var open by rememberSaveable(card.id) { mutableStateOf(false) }
    val names = all.filter { it.id in containing }.map { it.name }
    SectionHeader("Коллекции", Modifier.padding(horizontal = 4.dp))
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(if (names.isEmpty()) "Слово пока не входит ни в одну вашу коллекцию." else names.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        GhostButton(if (names.isEmpty()) "Добавить в коллекцию" else "Изменить коллекции", { open = true }, icon = AlbionIcons.Add)
    }
    if (open) com.marat.albion.ui.collections.AddToCollectionsSheet(card) { open = false }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Hairline()
    }
}

private fun stateName(s: Int) = when (s) {
    CardState.LEARNING -> "Изучается"
    CardState.REVIEW -> "Повторение"
    CardState.RELEARNING -> "Переучивается"
    else -> "Новая"
}
