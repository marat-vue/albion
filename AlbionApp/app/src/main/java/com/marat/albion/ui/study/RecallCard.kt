package com.marat.albion.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Cloze
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.repo.AutoGrade
import com.marat.albion.data.repo.CardFormat
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.games.AnswerField
import com.marat.albion.ui.games.GameAnswerActions
import com.marat.albion.ui.games.VerdictLine
import com.marat.albion.ui.games.annotateMistakes
import com.marat.albion.ui.games.shakeOn
import com.marat.albion.ui.games.termStyle
import com.marat.albion.ui.theme.AlbionFonts

/** Ввод на карточке зрелого слова: общий для поля карточки и панели действий. */
@Stable
internal class RecallState(private val card: StudyCard) {
    var input by mutableStateOf("")
    var verdict by mutableStateOf<Verdict?>(null)
        private set
    var shake by mutableIntStateOf(0)
        private set
    var validation by mutableStateOf<String?>(null)
        private set
    val focus = FocusRequester()

    val cloze get() = card.cloze.takeIf { card.format == CardFormat.Cloze }
    val rating: Rating? get() = verdict?.let { AutoGrade.rating(it, hinted = false) }

    /** Возвращает true, если ответ проверен (а не отклонён как пустой). */
    fun check(giveUp: Boolean): Boolean {
        if (verdict != null) return false
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return false }
        validation = null
        val term = card.card.term
        val task = cloze
        verdict = when {
            giveUp -> Verdict.Wrong
            task != null -> Cloze.check(input, task, term)
            else -> listOf(AnswerCheck.checkTerm(input, term), AnswerCheck.check(input, card.ownAnswers))
                .minBy { it.ordinal }
        }
        if (verdict == Verdict.Wrong) shake++
        return true
    }
}

@Composable
internal fun rememberRecallState(card: StudyCard) = remember(card.card.id, card.format) { RecallState(card) }

/** Скелет длины слова: «_ _ _ _   _ _» — часть формата, а не подсказка. */
private fun lengthSkeleton(term: String) = term.trim().split(Regex("\\s+")).joinToString("   ") { word ->
    if (AnswerCheck.isPlaceholder(word)) word else word.map { if (it.isLetter()) "_" else it.toString() }.joinToString(" ")
}

/** Поле карточки: задание до проверки, после — разбор и полная карточка. */
@Composable
internal fun RecallCard(card: StudyCard, irregular: IrregularData?, state: RecallState, audio: CardAudio, showRussian: Boolean) {
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        Modifier.fillMaxSize(), shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(.75.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp)) {
            if (card.leech) LeechLabel(card.lapses, Modifier.padding(bottom = 12.dp))
            val cloze = state.cloze
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SignalLabel(if (cloze != null) "Вставьте слово в пример" else "Вспомните слово по-английски")
                Spacer(Modifier.height(14.dp))
                if (cloze != null) {
                    Text(
                        if (state.verdict == null) cloze.masked else cloze.sentence,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = AlbionFonts.Reading),
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(card.card.meaningRu, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    if (card.card.pos.isNotBlank()) { Chip(card.card.pos); Spacer(Modifier.height(10.dp)) }
                    Text(card.card.meaningRu, style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                    if (state.verdict == null) {
                        Spacer(Modifier.height(14.dp))
                        Text(lengthSkeleton(card.card.term), style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                .padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
                state.verdict?.let { v ->
                    Spacer(Modifier.height(16.dp))
                    VerdictLine(v)
                    Spacer(Modifier.height(8.dp))
                    val correct = cloze?.answer ?: card.card.term
                    Text(
                        if (v != Verdict.Correct && state.input.isNotBlank()) annotateMistakes(correct, state.input) else AnnotatedString(correct),
                        style = termStyle(correct, 36), color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (v != Verdict.Correct && state.input.isNotBlank()) {
                        Text("Ваш ответ: ${state.input}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            if (state.verdict != null) {
                Spacer(Modifier.height(12.dp))
                CardBack(card.card, irregular, audio, showRussian = showRussian)
            }
        }
    }
}

/** Панель действий: поле ввода и «Проверить | Не знаю», после проверки — оценка и «Дальше». */
@Composable
internal fun RecallActions(
    card: StudyCard,
    state: RecallState,
    onChecked: () -> Unit,
    onNext: (Rating) -> Unit,
    onOwnAnswer: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state) { runCatching { state.focus.requestFocus() } }
    fun check(giveUp: Boolean) {
        if (!state.check(giveUp)) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onChecked()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rating = state.rating
        if (rating == null) {
            AnswerField(
                value = state.input, onChange = { state.input = it }, placeholder = "Ваш ответ", enabled = true,
                focus = state.focus, imeAction = ImeAction.Done, onIme = { check(false) }, modifier = Modifier.shakeOn(state.shake),
            )
            com.marat.albion.ui.components.FieldError(state.validation)
            GameAnswerActions(onCheck = { check(false) }, onSecondary = { check(true) })
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Оценка: ${rating.label}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.intervals[rating]?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            PrimaryButton("Дальше", onClick = { onNext(rating) })
            // Синоним тоже может быть верным ответом; в пропуске примера форма однозначна.
            if (state.verdict == Verdict.Wrong && state.cloze == null && state.input.isNotBlank()) {
                GhostButton("Мой ответ тоже верен", onClick = { onOwnAnswer(state.input.trim()) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
