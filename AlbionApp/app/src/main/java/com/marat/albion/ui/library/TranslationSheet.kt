package com.marat.albion.ui.library

import androidx.compose.ui.graphics.RectangleShape
import com.marat.albion.ui.components.QuietLinearProgressIndicator as LinearProgressIndicator
import com.marat.albion.ui.theme.IpaStyle
import androidx.compose.ui.draw.clip
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.marat.albion.ui.components.PaneBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.data.db.HighlightEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.marat.albion.ui.components.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.marat.albion.LocalAppContainer
import com.marat.albion.R
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.library.DictEntry
import com.marat.albion.data.library.Dictionary
import com.marat.albion.data.library.BookWordSource
import com.marat.albion.data.library.BookTranslationPhase
import com.marat.albion.ui.components.AudioButton
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.Hairline
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.study.rememberCardAudio

private sealed interface TranslationResult {
    data object Loading : TranslationResult
    data class Done(val text: String) : TranslationResult
    data class Failed(val message: String) : TranslationResult
}

/**
 * ML Kit переводит выбранное слово или фрагмент. Словарь и ваши карточки ищутся параллельно:
 * их значения доступны сразу, в том числе во время первой загрузки языковой модели.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSheet(
    text: String,
    isWord: Boolean,
    onDismiss: () -> Unit,
    /** Выделение, которого касается фрагмент; null — ещё не выделено. */
    highlight: HighlightEntity? = null,
    /** Создать выделение (с заметкой или без); null — выделять нельзя. */
    onHighlight: ((String) -> Unit)? = null,
    onNote: (HighlightEntity, String) -> Unit = { _, _ -> },
    onDeleteHighlight: (HighlightEntity) -> Unit = {},
    bookSource: BookWordSource? = null,
    bookExample: String = "",
    onOpenBookWords: () -> Unit = {},
    onBookWordSaved: (com.marat.albion.data.library.SavedBookWord) -> Unit = {},
) {
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val model by c.bookTranslator.state.collectAsStateWithLifecycle()
    var result by remember(text) { mutableStateOf<TranslationResult>(TranslationResult.Loading) }
    var cards by remember(text) { mutableStateOf<List<CardEntity>>(emptyList()) }
    var entries by remember(text) { mutableStateOf<List<DictEntry>?>(null) }
    var glosses by remember(text) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var details by rememberSaveable(text) { mutableStateOf(false) }

    var retry by remember(text) { mutableStateOf(0) }
    /** 0 — автоперевод, дальше — словарь; индексы не меняются после загрузки модели. */
    var chosen by remember(text) { mutableStateOf(0) }
    // После установки модели перевод повторяется сам.
    var autoRetried by remember(text) { mutableStateOf(false) }
    val failedBeforeInstall = model.ready && result is TranslationResult.Failed && !autoRetried
    LaunchedEffect(failedBeforeInstall) { if (failedBeforeInstall) { autoRetried = true; retry++ } }
    LaunchedEffect(text, retry) {
        result = TranslationResult.Loading
        glosses = emptyList()
        launch {
            cards = c.library.lookup(text)
            entries = c.dictionary.lookup(text)
            if (!isWord && !model.ready) glosses = lookupGlosses(c.dictionary, text)
        }
        try {
            result = TranslationResult.Done(c.bookTranslator.translate(text))
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            result = TranslationResult.Failed(error.message ?: "Не удалось выполнить перевод. Повторите попытку.")
            if (!isWord && glosses.isEmpty()) glosses = lookupGlosses(c.dictionary, text)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).navigationBarsPadding().imePadding().padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (isWord) "Перевод слова" else "Перевод фрагмента", Modifier.weight(1f))
                AudioButton(
                    playing = audio.playingKey == "read:$text",
                    onClick = {
                        val card = cards.firstOrNull { it.audio != null }
                        if (isWord && card != null) audio.play(Deck.byId(card.deck) ?: Deck.WORDS, card.audio, card.term, "read:$text")
                        else c.audio.speak(text, "read:$text")
                    },
                    size = 38.dp,
                    primary = false,
                )
                IconButton(onClick = onDismiss) {
                    Icon(AlbionIcons.Close, "Закрыть перевод", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val dictionary = entries.orEmpty()
            val options = dictionary.flatMap { entry -> entry.senses.filter { it.isNotBlank() }.map { entry to it } }
                .distinctBy { it.first.headword.lowercase() to it.second.lowercase() }
            val picked = options.getOrNull(chosen - 1)
            val firstEntry = picked?.first ?: options.firstOrNull()?.first
            val dictionaryMeaning = picked?.second
            val automatic = (result as? TranslationResult.Done)?.text?.takeIf { it.isNotBlank() }
            val ipa = cards.firstOrNull { it.ipa.isNotBlank() }?.ipa
            if (isWord && (firstEntry?.posLabel?.isNotBlank() == true || ipa != null)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    firstEntry?.posLabel?.takeIf { it.isNotBlank() }?.let { Chip(it) }
                    if (ipa != null) Text(ipa, style = IpaStyle)
                }
            }
            // Значение видно раньше сохранения и заметок, даже если перевод фраз ещё загружается.
            // Пока модель загружается или переводит, показываем готовое словарное значение.
            val failed = result as? TranslationResult.Failed
            val installed = model.ready
            val provisional = if (automatic == null)
                options.firstOrNull()?.second ?: cards.firstOrNull { it.meaningRu.isNotBlank() }?.meaningRu else null
            val meaning = dictionaryMeaning ?: automatic ?: provisional
            Spacer(Modifier.height(12.dp))
            if (meaning != null) {
                Text(meaning, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(when {
                    dictionaryMeaning != null -> "Словарь"
                    provisional != null && options.isEmpty() -> "Из ваших карточек"
                    provisional != null && failed != null -> "Словарь"
                    provisional != null -> "Словарь · готовлю автоперевод…"
                    else -> "Автоперевод"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                if (dictionaryMeaning == null && automatic != null) GoogleTranslationAttribution(Modifier.padding(top = 8.dp))
                if (chosen == 0 && provisional != null && failed == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                if (chosen == 0 && provisional != null && failed != null) {
                    // Перевод по контексту не получился — словарь уже на экране, ошибка модели второстепенна.
                    Text(if (installed) "Автоперевод недоступен: ${failed.message}" else "Автоперевод не установлен — показано словарное значение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (installed) TextButton(onClick = { result = TranslationResult.Loading; retry++ }) { Text("Повторить перевод") }
                }
            } else {
                when (val r = result) {
                    TranslationResult.Loading -> {
                        Text(if (model.phase == BookTranslationPhase.Checking) "Подготавливаю офлайн-перевод…" else "Перевожу…",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp),
                            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                    }
                    is TranslationResult.Failed -> if (installed) {
                        com.marat.albion.ui.components.FieldError(r.message)
                        TextButton(onClick = { result = TranslationResult.Loading; retry++ }) { Text("Повторить перевод") }
                    } else {
                        // Модель не установлена — это не ошибка: установить её можно ниже.
                        Text(r.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text("Перевод не найден. Значение можно указать при сохранении.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (glosses.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Eyebrow("Перевод по словам")
                    WordGlosses(glosses.take(4))
                }
            }


            // Полное значение в меню; выбранное уходит в карточку при сохранении.
            val choices = listOf(automatic?.let { "$it · автоперевод" } ?: "Автоперевод") + options.map { it.second }
            if (choices.size > 1) {
                Spacer(Modifier.height(14.dp))
                com.marat.albion.ui.components.OptionPicker("Значение · ${choices.size}", choices,
                    chosen.coerceAtLeast(0), { chosen = it })
            }
            val hasDetails = dictionary.sumOf { it.senses.size } > 1 || cards.isNotEmpty() ||
                (isWord && automatic != null && !automatic.equals(meaning, ignoreCase = true)) ||
                (meaning == null && glosses.size > 4) ||
                dictionary.any { !it.headword.equals(text, ignoreCase = true) }
            if (hasDetails) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { details = !details }.semantics { stateDescription = if (details) "Развёрнуто" else "Свёрнуто" }
                    .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Словарь, автоперевод и ваши карточки", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    com.marat.albion.ui.components.DisclosureIndicator(details, Modifier.padding(start = 12.dp))
                }
                AnimatedVisibility(details) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                        dictionary.forEach { DictionaryArticle(it, showHeadword = !it.headword.equals(text, ignoreCase = true)) }
                        if (isWord && automatic != null && !automatic.equals(meaning, ignoreCase = true)) {
                            Column {
                                Eyebrow("Автоперевод")
                                Text(automatic, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                GoogleTranslationAttribution(Modifier.padding(top = 8.dp))
                            }
                        }
                        if (meaning == null && glosses.size > 4) WordGlosses(glosses.drop(4))
                        if (cards.isNotEmpty()) {
                            Eyebrow("В ваших карточках")
                            cards.forEach { DictionaryEntry(it) }
                        }
                    }
                }
            }

            if (!model.ready) {
                Spacer(Modifier.height(12.dp))
                com.marat.albion.ui.components.Disclosure("Офлайн-перевод", model.summary) { BookTranslationControls() }
            }
            if (bookSource != null || onHighlight != null) {
                Spacer(Modifier.height(16.dp))
                Hairline()
                if (bookSource != null) {
                    BookWordAction(text, cards, dictionary, automatic, bookSource, bookExample, onOpenBookWords,
                        suggestedTerm = if (isWord && firstEntry != null) firstEntry.headword
                            else cards.firstOrNull { it.meaningRu.isNotBlank() }?.term ?: text,
                        suggestedMeaning = meaning, onSaved = onBookWordSaved)
                }
                if (onHighlight != null) HighlightSection(highlight, onHighlight, onNote, onDeleteHighlight)
            }

        }
    }
}

private val ARTICLES = setOf("a", "an", "the")
private val WORD = Regex("[A-Za-z][A-Za-z'’-]*")

private suspend fun lookupGlosses(dictionary: Dictionary, text: String): List<Pair<String, String>> =
    WORD.findAll(text).map { it.value }.distinctBy { it.lowercase() }
        .filter { it.lowercase() !in ARTICLES }.take(16).toList().mapNotNull { word ->
            dictionary.lookup(word).firstOrNull()?.let { entry ->
                word to entry.senses.take(2).joinToString("; ") { it.split(", ").take(2).joinToString(", ") }
            }
        }

/** Official, unmodified Google Translate attribution in the appropriate colour scheme. */
@Composable
private fun GoogleTranslationAttribution(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Image(painterResource(if (dark) R.drawable.google_translate_attribution_white else R.drawable.google_translate_attribution),
        contentDescription = "Powered by Google Translate",
        modifier = modifier.height(16.dp).aspectRatio(537f / 36f))
}

@Composable
private fun WordGlosses(glosses: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        glosses.forEach { (word, meaning) ->
            Column {
                Text(word, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DictionaryArticle(entry: DictEntry, showHeadword: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showHeadword) Text(entry.headword, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (entry.posLabel.isNotEmpty()) Chip(entry.posLabel)
        }
        Spacer(Modifier.height(6.dp))
        entry.senses.forEachIndexed { i, sense ->
            Row(Modifier.padding(vertical = 2.dp)) {
                if (entry.senses.size > 1) Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp).padding(top = 3.dp))
                Text(sense, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DictionaryEntry(card: CardEntity) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.term, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            if (card.ipa.isNotBlank()) Text(card.ipa, style = IpaStyle)
            Spacer(Modifier.weight(1f))
            LevelBadge(card.level)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(card.pos)
            Chip(Deck.byId(card.deck)?.title ?: card.deck)
        }
        Spacer(Modifier.height(12.dp))
        com.marat.albion.ui.components.WordMeanings(card, showRussian = true, showDefinitions = true)
    }
}

/**
 * Выделение и заметка: «Выделить» / «Заметка» для нового фрагмента; для уже выделенного —
 * цитата, заметка, правка и удаление.
 */
@Composable
private fun HighlightSection(
    highlight: HighlightEntity?,
    onHighlight: (String) -> Unit,
    onNote: (HighlightEntity, String) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
) {
    var editing by remember(highlight?.id) { mutableStateOf(false) }
    var draft by remember(highlight?.id) { mutableStateOf(highlight?.note.orEmpty()) }
    // Только что созданное выделение — показываем «Выделено», пока не пришла запись из БД.
    var justAdded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Поле заметки сразу получает фокус — клавиатура открывается без лишнего касания.
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (highlight != null) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RectangleShape).padding(14.dp)) {
                Eyebrow("Выделено")
                Spacer(Modifier.height(6.dp))
                Text(highlight.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 4)
                if (highlight.note.isNotBlank() && !editing) {
                    Spacer(Modifier.height(8.dp))
                    Text(highlight.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (editing) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Ваша заметка") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = MaterialTheme.colorScheme.outline, cursorColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                editing -> {
                    SmallAction("Сохранить", AlbionIcons.Check) {
                        if (highlight != null) onNote(highlight, draft) else { onHighlight(draft); justAdded = true }
                        editing = false
                    }
                    SmallAction("Отмена", AlbionIcons.Close) { editing = false; draft = highlight?.note.orEmpty() }
                }
                highlight != null -> {
                    SmallAction(if (highlight.note.isBlank()) "Добавить заметку" else "Изменить заметку", AlbionIcons.EditNote) { editing = true }
                    SmallAction("Удалить выделение", AlbionIcons.Delete) { onDelete(highlight); justAdded = false }
                }
                justAdded -> Eyebrow("Выделено", Modifier.padding(vertical = 8.dp))
                else -> {
                    SmallAction("Выделить", AlbionIcons.BorderColor) { onHighlight(""); justAdded = true }
                    SmallAction("Заметка", AlbionIcons.EditNote) { editing = true }
                }
            }
        }
    }
}

/** Действие над фрагментом: пара таких кнопок делит строку поровну. */
@Composable
private fun RowScope.SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).clip(RectangleShape).border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
