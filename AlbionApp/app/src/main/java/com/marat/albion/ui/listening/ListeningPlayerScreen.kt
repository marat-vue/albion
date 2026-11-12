package com.marat.albion.ui.listening

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.marat.albion.LocalAppContainer
import com.marat.albion.audio.ListeningService
import com.marat.albion.audio.ListeningState
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.library.BookWordSource
import com.marat.albion.data.listening.ListeningPracticeMode
import com.marat.albion.data.listening.ListeningTrack
import com.marat.albion.data.listening.Segment
import com.marat.albion.data.listening.Transcript
import com.marat.albion.data.listening.wordRangeAt
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.LoadingState
import com.marat.albion.ui.components.EmptyState
import com.marat.albion.ui.components.RoundIconButton
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.components.SegmentedPicker
import com.marat.albion.ui.components.ThinSlider
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.library.TranslationSheet
import com.marat.albion.ui.nav.DictionaryRoute
import com.marat.albion.ui.nav.ListeningPracticeRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.AlbionFonts
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.selectedContainer
import com.marat.albion.ui.theme.selectedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.75f, 1f, 1.25f)

/** Слово, которое открыли в словаре: само слово и фраза-пример. */
private data class WordPick(val word: String, val sentence: String, val resume: Boolean)

/**
 * Плеер с синхронным текстом: фразы списком, текущее слово подсвечено, текст сам
 * прокручивается за чтецом (пока вы не листаете сами). Касание слова — словарь,
 * касание времени у фразы — переход к ней. Повтор фразы, A–B и скорость 0.75/1/1.25.
 */
@Composable
fun ListeningPlayerScreen(trackId: String, navigator: Navigator) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val player = c.listening
    val state by player.state.collectAsStateWithLifecycle()
    var track by remember(trackId) { mutableStateOf<ListeningTrack?>(null) }
    var transcript by remember(trackId) { mutableStateOf<Transcript?>(null) }
    var error by remember(trackId) { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(player.positionMs) }
    var pick by remember { mutableStateOf<WordPick?>(null) }
    var practice by remember { mutableStateOf(false) }
    var collectionCard by remember { mutableStateOf<CardEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(trackId) {
        try {
            val t = c.listeningLibrary.track(trackId) ?: run { error = "Запись не найдена"; return@LaunchedEffect }
            track = t
            transcript = c.listeningLibrary.transcript(t)
            if (player.state.value.trackId != t.id) {
                val saved = c.listeningLibrary.observeProgress().first()[t.id]?.positionMs ?: 0L
                // Дослушали до конца — начинаем сначала.
                val start = if (t.meta.durationMs > 0 && saved > t.meta.durationMs - 3_000) 0L else saved
                player.load(t, start)
            }
            position = player.positionMs
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Не удалось открыть запись: ${e.message ?: "файл повреждён"}" }
    }
    // Подключение к MediaSessionService: служба держит звук в фоне и показывает уведомление.
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, ListeningService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        onDispose { MediaController.releaseFuture(future) }
    }
    // Позиция опрашивается только во время воспроизведения.
    LaunchedEffect(state.playing) {
        position = player.positionMs
        while (state.playing) {
            delay(50)
            position = player.positionMs
        }
    }

    fun seek(ms: Long) { player.seekTo(ms); position = ms }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(navigator::back, track?.meta?.title) {
                RoundIconButton(AlbionIcons.EditNote, "Практика по тексту", { practice = true }, filled = true,
                    enabled = transcript?.segments?.isNotEmpty() == true)
            }
            val t = track
            val tr = transcript
            when {
                error != null -> EmptyState("Запись не открылась", error.orEmpty(), Modifier.padding(gutter))
                t == null || tr == null -> LoadingState("Открываю запись")
                tr.segments.isEmpty() -> EmptyState("Текста нет", "В файле с текстом нет фраз с временем.", Modifier.padding(gutter))
                else -> {
                    TranscriptList(t, tr, { position }, state, Modifier.weight(1f),
                        onSeek = { seg -> player.seekToPhrase(seg.s, seg.e); position = seg.s },
                        onWord = { word, sentence ->
                            val wasPlaying = state.playing
                            player.pause()
                            pick = WordPick(word, sentence, wasPlaying)
                        })
                    PlayerControls(tr, position, state, onSeek = ::seek, player = player)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 180.dp, start = 12.dp, end = 12.dp)) {
            Snackbar(it)
        }
    }

    pick?.let { p ->
        val t = track
        TranslationSheet(
            text = p.word, isWord = true,
            onDismiss = { pick = null; if (p.resume) player.resume() },
            bookSource = BookWordSource("listening:$trackId", t?.meta?.title.orEmpty(), "Аудио с текстом"),
            bookExample = p.sentence,
            onOpenBookWords = { pick = null; navigator.open(DictionaryRoute(Deck.BOOK_WORDS)) },
            onBookWordSaved = { saved ->
                pick = null
                if (p.resume) player.resume()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        if (saved.added) "«${saved.card.term}» — в «Слова из книг»" else "Это значение уже есть в «Слова из книг».",
                        actionLabel = "В коллекцию", withDismissAction = true, duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) collectionCard = saved.card
                }
            },
        )
    }
    collectionCard?.let { card -> com.marat.albion.ui.collections.AddToCollectionsSheet(card) { collectionCard = null } }

    if (practice) ScrollableSheet("Практика по тексту", { practice = false }) {
        Text("Начнём с фразы, которая звучит сейчас.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ListeningPracticeMode.entries.forEach { mode ->
            com.marat.albion.ui.components.Panel(Modifier.fillMaxWidth(), onClick = {
                practice = false
                player.pause()
                val from = transcript?.segmentAt(player.positionMs)?.coerceAtLeast(0) ?: 0
                navigator.open(ListeningPracticeRoute(trackId, mode, from))
            }, contentPadding = PaddingValues(16.dp)) {
                Text(mode.title, style = MaterialTheme.typography.titleMedium)
                Text(mode.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TranscriptList(
    track: ListeningTrack,
    transcript: Transcript,
    position: () -> Long,
    state: ListeningState,
    modifier: Modifier,
    onSeek: (Segment) -> Unit,
    onWord: (word: String, sentence: String) -> Unit,
) {
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    var follow by remember { mutableStateOf(true) }
    // Позиция меняется 20 раз в секунду, а список перекомпонуется только при смене фразы или слова.
    val current by remember(transcript) { derivedStateOf { transcript.segmentAt(position()) } }
    val word by remember(transcript) {
        derivedStateOf { position().let { ms -> if (transcript.isInside(current, ms)) transcript.wordAt(current, ms) else -1 } }
    }
    // Пока вы листаете сами, текст не дёргается за чтецом; кнопка возвращает слежение.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) follow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(current, follow) {
        if (!follow || current < 0) return@LaunchedEffect
        val index = current + HEADER_ITEMS
        val visible = listState.layoutInfo.visibleItemsInfo
        val offset = -listState.layoutInfo.viewportSize.height / 4
        if (reduced || visible.none { it.index == index }) listState.scrollToItem(index, offset)
        else listState.animateScrollToItem(index, offset)
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(nested),
            state = listState,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 4.dp, bottom = 48.dp),
        ) {
            item(key = "header") { TrackHeader(track, transcript) }
            itemsIndexed(transcript.segments, key = { i, _ -> i }) { i, seg ->
                SegmentRow(transcript, i, seg, active = i == current, activeWord = if (i == current) word else -1,
                    looped = state.loop?.phrase == true && state.loop.startMs == seg.s,
                    onSeek = { onSeek(seg) }, onWord = { w -> onWord(w, seg.text) })
            }
        }
        if (!follow) Box(Modifier.align(Alignment.BottomEnd).padding(end = gutter, bottom = 12.dp)) {
            RoundIconButton(ListeningIcons.Follow, "Следить за чтецом", { follow = true }, filled = true)
        }
    }
}

private const val HEADER_ITEMS = 1

@Composable
private fun TrackHeader(track: ListeningTrack, transcript: Transcript) {
    val meta = track.meta
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Eyebrow(listOfNotNull(meta.level.takeIf(String::isNotBlank), if (transcript.wordLevel) "Пословно" else "По фразам").joinToString(" · "))
        if (meta.author.isNotBlank()) Text(meta.author, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        val voice = listOfNotNull(meta.reader.takeIf(String::isNotBlank)?.let { "Читает $it" }, meta.accent.takeIf(String::isNotBlank)?.let(::accentLabel))
        if (voice.isNotEmpty()) Text(voice.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val attribution = meta.attribution.ifBlank { listOf(meta.source, meta.license).filter(String::isNotBlank).joinToString(", ") }
        if (attribution.isNotBlank()) Text(attribution, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SegmentRow(
    transcript: Transcript,
    index: Int,
    segment: Segment,
    active: Boolean,
    activeWord: Int,
    looped: Boolean,
    onSeek: () -> Unit,
    onWord: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val text = segmentText(transcript, index, segment, active, activeWord)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val pickWord by rememberUpdatedState(onWord)
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) scheme.surfaceContainerLow else scheme.background, MaterialTheme.shapes.small)
            .then(if (looped) Modifier.border(1.dp, scheme.outline, MaterialTheme.shapes.small) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            formatTime(segment.s),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AlbionFonts.Tech),
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 52.dp).heightIn(min = 32.dp)
                .clickable(role = Role.Button, onClickLabel = "Перейти к фразе", onClick = onSeek)
                .padding(start = 4.dp, top = 6.dp, end = 8.dp)
                .semantics { contentDescription = "Перейти к фразе ${formatTime(segment.s)}" },
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AlbionFonts.Reading),
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            onTextLayout = { layout = it },
            modifier = Modifier.weight(1f).padding(end = 4.dp)
                .semantics { if (active) stateDescription = "Звучит сейчас" }
                .pointerInput(segment.text) {
                    detectTapGestures { pos ->
                        val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                        val range = wordRangeAt(segment.text, offset)
                        if (range != null) pickWord(segment.text.substring(range)) else seek()
                    }
                },
        )
    }
}

/** Звучащее слово инвертировано, уже прочитанные слова фразы — основным цветом, впереди — приглушены. */
@Composable
private fun segmentText(transcript: Transcript, index: Int, segment: Segment, active: Boolean, activeWord: Int): AnnotatedString {
    if (!active || !transcript.wordLevel || activeWord < 0) return AnnotatedString(segment.text)
    val scheme = MaterialTheme.colorScheme
    val ranges = transcript.wordRanges(index)
    val spoken = ranges.getOrNull(activeWord)
    val upcomingFrom = spoken?.last?.plus(1) ?: return AnnotatedString(segment.text)
    return buildAnnotatedString {
        append(segment.text.substring(0, spoken.first))
        withStyle(SpanStyle(background = scheme.selectedContainer, color = scheme.selectedContent)) { append(segment.text.substring(spoken)) }
        withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(segment.text.substring(upcomingFrom)) }
    }
}

@Composable
private fun PlayerControls(
    transcript: Transcript,
    position: Long,
    state: ListeningState,
    onSeek: (Long) -> Unit,
    player: com.marat.albion.audio.ListeningPlayer,
) {
    val scheme = MaterialTheme.colorScheme
    val duration = maxOf(state.durationMs, transcript.durationMs, 1L)
    val current = transcript.segmentAt(position)
    Column(
        Modifier.fillMaxWidth().background(scheme.background)
            .border(.75.dp, scheme.outlineVariant)
            .navigationBarsPadding().padding(horizontal = gutter, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(position), style = MaterialTheme.typography.labelSmall.copy(fontFamily = AlbionFonts.Tech))
            ThinSlider(position.coerceIn(0, duration).toFloat(), { onSeek(it.toLong()) }, 0f..duration.toFloat(), steps = 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Позиция записи" })
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall.copy(fontFamily = AlbionFonts.Tech),
                color = scheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(ListeningIcons.PreviousPhrase, "Предыдущая фраза", {
                // Как в плеерах: в начале фразы — к предыдущей, иначе — к началу текущей.
                val seg = transcript.segments.getOrNull(current)
                val target = if (seg != null && position - seg.s > 1_500) current else (current - 1).coerceAtLeast(0)
                transcript.segments.getOrNull(target)?.let { player.seekToPhrase(it.s, it.e); onSeek(it.s) }
            })
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(scheme.primary, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = if (state.playing) "Пауза" else "Слушать") { player.toggle() }
                    .semantics { contentDescription = if (state.playing) "Пауза" else "Слушать" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (state.playing) ListeningIcons.Pause else AlbionIcons.PlayArrow, null, tint = scheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(20.dp))
            RoundIconButton(ListeningIcons.NextPhrase, "Следующая фраза", {
                transcript.segments.getOrNull(current + 1)?.let { player.seekToPhrase(it.s, it.e); onSeek(it.s) }
            }, enabled = current + 1 < transcript.segments.size)
        }
        // Скорость — своей строкой: рядом с повторами ей не хватало ширины, и сегменты вставали столбиком.
        SegmentedPicker(SPEEDS.map { speedLabel(it) }, SPEEDS.indexOf(state.speed).coerceAtLeast(0),
            { player.setSpeed(SPEEDS[it]) }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val phrase = transcript.segments.getOrNull(current.coerceAtLeast(0))
            LoopToggle("Фраза", state.loop?.phrase == true, Modifier.weight(1f), "Повтор текущей фразы") {
                phrase?.let { player.togglePhraseLoop(it.s, it.e) }
            }
            val abLabel = when {
                state.loop != null && !state.loop.phrase -> "A–B ✕"
                state.pendingA != null -> "Точка B"
                else -> "A–B"
            }
            LoopToggle(abLabel, state.pendingA != null || (state.loop != null && !state.loop.phrase), Modifier.weight(1f),
                "Повтор отрезка: отметьте начало и конец") { player.markAB() }
        }
    }
}

private fun speedLabel(speed: Float) = when (speed) { 0.75f -> "0.75×"; 1.25f -> "1.25×"; else -> "1×" }

/** Переключатель повтора: выбранное состояние — инверсная плашка, как у остальных выборов. */
@Composable
private fun LoopToggle(label: String, on: Boolean, modifier: Modifier, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .background(if (on) scheme.selectedContainer else scheme.background, MaterialTheme.shapes.medium)
            .border(1.dp, if (on) scheme.selectedContainer else scheme.outline, MaterialTheme.shapes.medium)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = description; stateDescription = if (on) "Включён" else "Выключен" }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ListeningIcons.RepeatPhrase, null, Modifier.size(16.dp), tint = if (on) scheme.selectedContent else scheme.onSurface)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.selectedContent else scheme.onSurface, maxLines = 1)
        }
    }
}
