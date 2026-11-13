package com.marat.albion.ui.games

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Cloze
import com.marat.albion.data.games.Verdict
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.study.highlightTerms
import com.marat.albion.ui.study.rememberCardAudio
import com.marat.albion.ui.theme.AlbionFonts
import com.marat.albion.ui.theme.IpaStyle
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility

/** Поле ответа и «Проверить | Не знаю» — общий низ «Диктанта» и «Слова в контексте». */
@Composable
private fun WordAnswerBar(
    verdict: Verdict?,
    input: String,
    onInput: (String) -> Unit,
    focus: FocusRequester,
    shake: Int,
    validation: String?,
    onCheck: (giveUp: Boolean) -> Unit,
    onNext: () -> Unit,
) {
    if (verdict == null) {
        AnswerField(
            value = input, onChange = onInput, placeholder = "Ваш ответ", enabled = true, focus = focus,
            imeAction = ImeAction.Done, onIme = { onCheck(false) }, modifier = Modifier.padding(top = 10.dp).shakeOn(shake),
        )
    }
    com.marat.albion.ui.components.FieldError(validation)
    AnimatedContent(
        targetState = verdict != null,
        transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        label = "actions",
        modifier = Modifier.padding(vertical = 12.dp),
    ) { answered ->
        if (answered) NextDock(verdict != Verdict.Wrong, title = when (verdict) { Verdict.Typo -> "Почти"; Verdict.Wrong -> "Неверно"; else -> "Верно" }, onNext = onNext)
        else GameAnswerActions(onCheck = { onCheck(false) }, onSecondary = { onCheck(true) })
    }
}

/** Правильный ответ крупно: при опечатке отличающиеся буквы подчёркнуты. */
@Composable
private fun CorrectAnswer(correct: String, input: String, verdict: Verdict) {
    AnswerLabel(verdict)
    Spacer(Modifier.height(6.dp))
    Text(
        if (verdict != Verdict.Correct && input.isNotBlank()) annotateMistakes(correct, input) else AnnotatedString(correct),
        style = termStyle(correct, 36), color = MaterialTheme.colorScheme.onSurface,
    )
    if (verdict != Verdict.Correct && input.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text("Ваш ответ: $input", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ диктант

@Composable
fun DictationRound(vm: GameViewModel, card: CardEntity) {
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val deck = Deck.byId(card.deck) ?: Deck.WORDS
    var input by rememberSaveable(card.id) { mutableStateOf("") }
    var verdict by rememberSaveable(card.id) { mutableStateOf<Verdict?>(null) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val focus = remember(card.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val key = "dictation:${card.id}"
    fun play(rate: Float = 1f) = c.audio.play(deck, card.audio, card.term, key, rate)

    LaunchedEffect(card.id) {
        runCatching { focus.requestFocus() }
        play()
    }

    fun check(giveUp: Boolean) {
        if (verdict != null) return
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return }
        validation = null
        val v = if (giveUp) Verdict.Wrong else AnswerCheck.checkTerm(input, card.term)
        verdict = v
        if (v == Verdict.Wrong) shake++
        // Диктант проверяет слух и написание, а не память значения — в FSRS не идёт.
        vm.record(v != Verdict.Wrong, if (v == Verdict.Wrong) Mistake(card.ipa.ifBlank { "на слух" }, input, card.term, promptIsIpa = card.ipa.isNotBlank()) else null)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(centered = true, label = "Запишите, что услышали") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PracticeAudioButton(playing = audio.playingKey == key, onClick = { play() }, size = 64.dp)
                    GhostButton("Медленнее", onClick = { play(0.7f) }, icon = AlbionIcons.SlowMotionVideo)
                }
                if (card.pos.isNotBlank() && !imeOpen()) {
                    Spacer(Modifier.height(12.dp))
                    Chip(card.pos)
                }
            }
            AnimatedVisibility(verdict != null, enter = fadeIn(tween(300)) + slideInVertically(tween(420)) { it / 3 }) {
                val v = verdict ?: Verdict.Wrong
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CorrectAnswer(card.term, input, v)
                    if (card.ipa.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(card.ipa, style = IpaStyle)
                    }
                    Spacer(Modifier.height(12.dp))
                    NoteCard {
                        Eyebrow("Значение")
                        Spacer(Modifier.height(4.dp))
                        Text(card.meaningRu, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        WordAnswerBar(verdict, input, { input = it }, focus, shake, validation, ::check) { vm.next() }
    }
}

// ------------------------------------------------------------------ слово в контексте

@Composable
fun ClozeRound(vm: GameViewModel, card: CardEntity) {
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val deck = Deck.byId(card.deck) ?: Deck.WORDS
    val task = remember(card.id) { vm.cloze(card) } ?: return
    var input by rememberSaveable(card.id) { mutableStateOf("") }
    var verdict by rememberSaveable(card.id) { mutableStateOf<Verdict?>(null) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val focus = remember(card.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(card.id) { runCatching { focus.requestFocus() } }

    fun check(giveUp: Boolean) {
        if (verdict != null) return
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return }
        validation = null
        val v = if (giveUp) Verdict.Wrong else Cloze.check(input, task, card.term)
        verdict = v
        if (v == Verdict.Wrong) shake++
        vm.record(v != Verdict.Wrong, if (v == Verdict.Wrong) Mistake(task.masked, input, task.answer) else null, verdict = v)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        audio.play(deck, card.audio, card.term, "term:${card.id}")
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(centered = true, label = "Вставьте слово в пример") {
                Text(
                    if (verdict == null) AnnotatedString(task.masked) else highlightTerms(task.sentence, listOf(task.answer)),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = AlbionFonts.Reading),
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                // Часть речи над пояснением: в строку длинное толкование сжимается в узкую колонку.
                if (card.pos.isNotBlank() && !imeOpen()) { Chip(card.pos); Spacer(Modifier.height(8.dp)) }
                Text(card.meaningRu, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            AnimatedVisibility(verdict != null, enter = fadeIn(tween(300)) + slideInVertically(tween(420)) { it / 3 }) {
                val v = verdict ?: Verdict.Wrong
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CorrectAnswer(task.answer, input, v)
                    if (v == Verdict.Typo && !task.answer.equals(card.term, ignoreCase = true)) {
                        Spacer(Modifier.height(6.dp))
                        Text("Слово — ${card.term}; в этом предложении нужна форма ${task.answer}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(10.dp))
                    PracticeAudioButton(playing = audio.playingKey == "term:${card.id}",
                        onClick = { audio.play(deck, card.audio, card.term, "term:${card.id}") }, size = 40.dp)
                }
            }
        }
        WordAnswerBar(verdict, input, { input = it }, focus, shake, validation, ::check) { vm.next() }
    }
}
