package com.marat.albion.ui.games

import com.marat.albion.ui.theme.controlBorder
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.LocalNarrow
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import androidx.compose.animation.animateColorAsState
import com.marat.albion.ui.theme.quietContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.Verdict
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.study.rememberCardAudio

/**
 * «Соберите предложение»: плашки со словами примера перемешаны внизу, касание переносит
 * слово в ответ, касание слова в ответе — возвращает его. Подсказка ставит следующее
 * верное слово, но такой ответ уже не засчитывается как «без ошибок».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SentenceRound(vm: GameViewModel, card: CardEntity) {
    val puzzle = remember(card.id) { vm.puzzle(card) } ?: return
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val order = remember(card.id) { vm.shuffledOrder(card, puzzle.tokens.size) }
    // Индексы плашек (в puzzle.tokens) в том порядке, как их поставил пользователь.
    val placed = rememberSaveable(card.id, saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<Int>()
    }
    var verdict by rememberSaveable(card.id) { mutableStateOf<Verdict?>(null) }
    var hinted by rememberSaveable(card.id) { mutableStateOf(false) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val answer = placed.map { puzzle.tokens[it] }
    val wrong = if (verdict == Verdict.Wrong) puzzle.wrongPositions(answer) else emptySet()
    val speakKey = "sentence:${card.id}"
    // Перевод предложения: без него порядок слов приходится угадывать. Переводит ИИ (Groq), если добавлен ключ.
    val ai by c.translator.status.collectAsStateWithLifecycle()
    val canTranslate = ai.ready
    val translation = vm.sentenceTranslations[card.id]
    androidx.compose.runtime.LaunchedEffect(canTranslate) { vm.prefetchTranslations(canTranslate) }
    var editingTranslation by rememberSaveable(card.id) { mutableStateOf(false) }

    fun check() {
        if (verdict != null) return
        if (placed.size < puzzle.tokens.size) { validation = "Добавьте оставшиеся слова: ${placed.size} из ${puzzle.tokens.size}."; shake++; return }
        validation = null
        val ok = puzzle.isCorrect(answer)
        verdict = if (ok) Verdict.Correct else Verdict.Wrong
        if (!ok) shake++
        val clean = ok && !hinted
        vm.record(clean, if (clean) null else Mistake(card.term, if (ok) "с подсказкой" else answer.joinToString(" ") + puzzle.ending, puzzle.original))
        c.audio.speak(puzzle.original, speakKey)
    }

    /** Убирает неверно поставленный хвост и добавляет следующее верное слово. */
    fun hint() {
        if (verdict != null) return
        hinted = true
        val prefix = answer.zip(puzzle.tokens).takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }.size
        while (placed.size > prefix) placed.removeAt(placed.lastIndex)
        val need = puzzle.tokens.getOrNull(prefix) ?: return
        order.firstOrNull { it !in placed && puzzle.tokens[it].equals(need, ignoreCase = true) }?.let { placed += it }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp)) {
            TaskCard(label = "Соберите предложение") {
            val shownTranslation = translation?.takeIf { it.isNotBlank() }
            if (shownTranslation != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(shownTranslation, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    // Перевод делает маленькая модель — его можно поправить, исправление сохранится.
                    com.marat.albion.ui.components.RoundIconButton(com.marat.albion.ui.components.AlbionIcons.EditNote, "Исправить перевод",
                        { editingTranslation = true }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            } else if (canTranslate) {
                Text("Переводим предложение…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(card.term)
                if (card.meaningRu.isNotBlank()) {
                    // Значение слова целиком: обрезанная подсказка («Используется перед исчисляемым существите…») не помогала.
                    Text(card.meaningRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.marat.albion.ui.components.SignalLabel("Ваша фраза", Modifier.weight(1f))
                com.marat.albion.ui.components.SignalLabel("${placed.size} / ${puzzle.tokens.size}")
            }
            Spacer(Modifier.height(12.dp))

            if (verdict == null && placed.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.marat.albion.ui.components.TextButton(onClick = { placed.removeAt(placed.lastIndex); validation = null }) { Text("Отменить слово") }
                com.marat.albion.ui.components.TextButton(onClick = { placed.clear(); validation = null }) { Text("Сбросить") }
            }
            // Ответ: поставленные плашки на линии «тетради».
            Column(
                Modifier.fillMaxWidth().heightIn(min = 104.dp).shakeOn(shake).quietContentSize(tween(if (com.marat.albion.ui.theme.LocalReducedMotion.current) 0 else 220)),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (placed.isEmpty()) Text("Нажимайте слова ниже по порядку. Нажмите слово в ответе, чтобы вернуть его.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    placed.forEachIndexed { pos, tokenIndex ->
                        WordTile(
                            text = puzzle.tokens[tokenIndex],
                            style = when {
                                verdict == Verdict.Correct -> TileStyle.Correct
                                pos in wrong -> TileStyle.Wrong
                                else -> TileStyle.Placed
                            },
                            onClick = { if (verdict == null) placed.removeAt(pos) },
                        )
                    }
                    if (puzzle.ending.isNotEmpty() && placed.size == puzzle.tokens.size) {
                        Text(puzzle.ending, style = TileText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp))
                    }
                }
                com.marat.albion.ui.components.ThinProgress(placed.size.toFloat() / puzzle.tokens.size.coerceAtLeast(1),
                    Modifier.padding(top = 14.dp), height = 2.dp)
            }
            }

            AnimatedContent(
                targetState = verdict != null,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
                label = "pool",
            ) { answered ->
                if (!answered) {
                    Column {
                    com.marat.albion.ui.components.SignalLabel("Доступные слова", Modifier.padding(top = 22.dp))
                    // Запас слов: поставленные остаются пустыми местами, чтобы плашки не прыгали.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        order.forEach { tokenIndex ->
                            val used = tokenIndex in placed
                            WordTile(
                                text = puzzle.tokens[tokenIndex],
                                style = if (used) TileStyle.Ghost else TileStyle.Idle,
                                onClick = { if (!used) placed += tokenIndex },
                            )
                        }
                    }
                    }
                } else {
                    NoteCard(Modifier.padding(top = 18.dp), tone = if (verdict == Verdict.Correct) NoteTone.Good else NoteTone.Bad) {
                        val v = verdict ?: Verdict.Wrong
                        Eyebrow(if (v == Verdict.Correct) "Предложение целиком" else "Правильный порядок",
                            color = if (v == Verdict.Correct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                        if (v == Verdict.Correct && hinted) {
                            Spacer(Modifier.height(4.dp))
                            Text("Собрано с подсказкой — в итоге не засчитано как «без ошибок».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                puzzle.original,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            PracticeAudioButton(
                                playing = audio.playingKey == speakKey,
                                onClick = { c.audio.speak(puzzle.original, speakKey) },
                                size = 40.dp,
                                primary = false,
                            )
                        }
                    }
                }
            }
        }

        com.marat.albion.ui.components.FieldError(validation)
        AnimatedContent(
            targetState = verdict != null,
            transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "actions",
            modifier = Modifier.padding(vertical = 12.dp),
        ) { answered ->
            if (answered) {
                NextDock(verdict == Verdict.Correct) { vm.next() }
            } else {
                GameAnswerActions(onCheck = { check() }, onSecondary = { hint() }, secondaryLabel = "Подсказка")
            }
        }
    }
    if (editingTranslation) {
        var draft by rememberSaveable(card.id) { mutableStateOf(translation.orEmpty()) }
        com.marat.albion.ui.components.PaneAlertDialog(
            onDismissRequest = { editingTranslation = false },
            title = { Text("Перевод предложения") },
            text = {
                androidx.compose.material3.OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), minLines = 2,
                    supportingText = { Text("Исправление сохранится и заменит перевод модели.") })
            },
            confirmButton = {
                com.marat.albion.ui.components.TextButton(onClick = { vm.editTranslation(card, draft); editingTranslation = false },
                    enabled = draft.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { com.marat.albion.ui.components.TextButton(onClick = { editingTranslation = false }) { Text("Отмена") } },
        )
    }
}

private enum class TileStyle { Idle, Placed, Ghost, Correct, Wrong }

private val TileText @Composable get() = MaterialTheme.typography.titleLarge
private val TileTextNarrow @Composable get() = MaterialTheme.typography.titleMedium

/** Плашка со словом. Ghost — пустое место, откуда слово уже взято. */
@Composable
private fun WordTile(text: String, style: TileStyle, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        when (style) {
            TileStyle.Correct -> MaterialTheme.colorScheme.tertiaryContainer
            TileStyle.Placed -> MaterialTheme.colorScheme.primary
            TileStyle.Wrong -> MaterialTheme.colorScheme.errorContainer
            TileStyle.Ghost -> MaterialTheme.colorScheme.surfaceContainerLow
            TileStyle.Idle -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        tween(240), label = "tileBg",
    )
    val fg by animateColorAsState(
        when (style) {
            TileStyle.Correct -> MaterialTheme.colorScheme.onTertiaryContainer
            TileStyle.Placed -> MaterialTheme.colorScheme.onPrimary
            TileStyle.Wrong -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        tween(240), label = "tileFg",
    )
    val border = when (style) {
        TileStyle.Idle -> MaterialTheme.colorScheme.controlBorder
        TileStyle.Placed -> MaterialTheme.colorScheme.primary
        TileStyle.Ghost -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)
        TileStyle.Correct -> MaterialTheme.colorScheme.tertiary
        TileStyle.Wrong -> MaterialTheme.colorScheme.error
    }
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = style == TileStyle.Idle || style == TileStyle.Placed, onClick = onClick)
            .padding(horizontal = if (LocalNarrow.current) 12.dp else 14.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            style = (if (LocalNarrow.current) TileTextNarrow else TileText).copy(textDecoration = if (style == TileStyle.Wrong) TextDecoration.LineThrough else null),
            color = fg,
            modifier = Modifier.alpha(if (style == TileStyle.Ghost) 0f else 1f),
        )
    }
}
