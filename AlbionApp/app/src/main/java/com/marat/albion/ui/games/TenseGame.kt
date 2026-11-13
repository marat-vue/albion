package com.marat.albion.ui.games

import com.marat.albion.ui.theme.IpaStyle
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.TenseTask
import com.marat.albion.data.games.Verdict
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.study.rememberCardAudio

/**
 * Тренажёр времён: пример с пропуском вместо формы неправильного глагола.
 * Режим выбора (V1 / V2 / V3 / «goed») или ввода; после ответа — правило и все формы.
 */
@Composable
fun TenseRound(vm: GameViewModel, card: CardEntity) {
    val task = remember(card.id) { vm.tense(card) } ?: return
    val data = remember(card.id) { vm.irregular(card) }
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val typing = vm.mode == 1
    val options = remember(card.id) { vm.tenseOptions(card, task) }
    var picked by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    var input by rememberSaveable(card.id) { mutableStateOf("") }
    var verdict by rememberSaveable(card.id) { mutableStateOf<Verdict?>(null) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val focus = remember(card.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val prompt = "${task.before.trim()} ___ (${task.infinitive}) ${task.after.trim()}".trim()

    // Озвучка формы, которая стоит в примере.
    val answerVariant = data?.forms?.getOrNull(task.formIndex)?.variants?.firstOrNull { it.text.equals(task.answer, ignoreCase = true) }
    fun playAnswer() = c.audio.play(Deck.IRREGULAR, answerVariant?.audio?.firstOrNull(), task.answer, "tense:${card.id}")

    LaunchedEffect(card.id, typing) { if (typing) runCatching { focus.requestFocus() } }

    fun finish(v: Verdict, answer: String) {
        verdict = v
        if (v == Verdict.Wrong) shake++
        vm.record(v == Verdict.Correct, Mistake(prompt, answer, task.answer))
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        playAnswer()
    }

    fun checkTyped(giveUp: Boolean = false) {
        if (verdict != null) return
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return }
        validation = null
        finish(if (giveUp) Verdict.Wrong else AnswerCheck.checkGrammar(input, task.accepted), input)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp)) {
            TaskCard(Modifier.shakeOn(shake), label = "Найдите нужную форму") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Какая форма нужна?", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    LevelBadge(card.level)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    sentence(task, filled = verdict?.let { shownAnswer(task, if (typing) input else picked, it) }),
                    style = promptStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(20.dp))

            if (typing) {
                AnswerField(
                    value = input,
                    onChange = { if (verdict == null) input = it },
                    placeholder = "Форма глагола",
                    enabled = verdict == null,
                    focus = focus,
                    imeAction = ImeAction.Done,
                    onIme = { checkTyped() },
                    struck = verdict == Verdict.Wrong, accepted = verdict == Verdict.Correct,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    options.forEachIndexed { index, option ->
                        val isAnswer = task.accepted.any { it.equals(option, ignoreCase = true) }
                        OptionButton(
                            text = option,
                            index = index,
                            state = when {
                                verdict == null -> OptionState.Idle
                                isAnswer -> OptionState.Correct
                                option == picked -> OptionState.Wrong
                                else -> OptionState.Dim
                            },
                            onClick = {
                                if (verdict == null) {
                                    picked = option
                                    finish(if (isAnswer) Verdict.Correct else Verdict.Wrong, option)
                                }
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(verdict != null, enter = fadeIn(tween(300)) + expandVertically()) {
                val v = verdict ?: Verdict.Wrong
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    if (typing) AnswerLabel(v)
                    if (typing && v != Verdict.Correct) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            AnnotatedString(task.answer),
                            style = promptStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    NoteCard {
                        Eyebrow(task.formLabel, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(task.rule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FormsColumn(task, data, Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            PracticeAudioButton(playing = audio.playingKey == "tense:${card.id}", onClick = ::playAnswer, size = 40.dp, primary = false)
                        }
                        answerVariant?.ipa?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text("${task.answer}  $it", style = IpaStyle)
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
            when {
                answered -> NextDock(verdict != Verdict.Wrong) { vm.next() }
                typing -> GameAnswerActions(onCheck = { checkTyped() }, onSecondary = { checkTyped(giveUp = true) })
                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/** Что вписать в пропуск после ответа: ваш вариант, если он верный (burned вместо burnt). */
private fun shownAnswer(task: TenseTask, answer: String?, verdict: Verdict): String =
    if (verdict == Verdict.Wrong || answer == null) task.answer
    else task.accepted.firstOrNull { AnswerCheck.normalizeGrammar(it) == AnswerCheck.normalizeGrammar(answer) } ?: task.answer

/** Предложение: пропуск-линия с подсказкой «(go)», после ответа — вписанная форма. */
private fun sentence(task: TenseTask, filled: String?): AnnotatedString = buildAnnotatedString {
    append(task.before)
    if (filled == null) {
        append(GAP_MARK)
        withStyle(SpanStyle(fontSize = 20.sp)) { append(" (${task.infinitive})") }
    } else {
        withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(filled) }
    }
    append(task.after)
}

/** Формы столбиком «V1 arise / V2 arose / V3 arisen» — нужная выделена; длинные варианты не рвут строку. */
@Composable
private fun FormsColumn(task: TenseTask, data: com.marat.albion.data.content.IrregularData?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        data?.forms.orEmpty().forEachIndexed { i, f ->
            val current = i == task.formIndex
            Row {
                Text(
                    "V${i + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp).alignByBaseline(),
                )
                Text(
                    f.variants.joinToString(" / ") { it.text },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (current) FontWeight.SemiBold else null,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
