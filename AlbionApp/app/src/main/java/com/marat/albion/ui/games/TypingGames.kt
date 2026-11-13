package com.marat.albion.ui.games

import com.marat.albion.ui.theme.Capsule

import com.marat.albion.ui.theme.controlBorder
import com.marat.albion.ui.theme.IpaStyle
import androidx.compose.ui.semantics.contentDescription
import com.marat.albion.ui.components.AlbionIcons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import com.marat.albion.ui.components.LocalNarrow
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import com.marat.albion.ui.theme.quietFloatAsState as animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Verdict
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.components.lerpColor
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.theme.AlbionFonts
import com.marat.albion.ui.study.rememberCardAudio

// ------------------------------------------------------------------ поле ввода

/**
 * Поле ответа без автокоррекции. Рамка выделяет фокус; подсказка не перекрывает курсор.
 */
@Composable
internal fun AnswerField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    focus: FocusRequester,
    imeAction: ImeAction,
    onIme: () -> Unit,
    modifier: Modifier = Modifier,
    struck: Boolean = false,
    /** Ответ принят — поле подкрашивается мятой. */
    accepted: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val on by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "focus")
    val narrow = LocalNarrow.current
    val shape = MaterialTheme.shapes.medium
    val border = when {
        struck -> MaterialTheme.colorScheme.error
        accepted -> MaterialTheme.colorScheme.tertiary
        else -> lerpColor(MaterialTheme.colorScheme.controlBorder, MaterialTheme.colorScheme.primary, on)
    }
    Column(modifier.fillMaxWidth()) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontFamily = AlbionFonts.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            fontSize = if (narrow) 24.sp else 28.sp,
            color = if (struck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }.focusRequester(focus).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .background(when {
                        struck -> MaterialTheme.colorScheme.errorContainer
                        accepted -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }, shape)
                    .border(if (focused) 2.dp else 1.dp, border, shape)
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlbionIcons.EditNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // Подсказка видна и в фокусе: поле открывается с клавиатурой, и иначе её никто не видел.
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                inner()
                }
                Spacer(Modifier.width(10.dp))
                SignalLabel("EN")
            }
        },
    )
    }
}

/** Правильный ответ, где пропущенные или искажённые буквы подчёркнуты. */
internal fun annotateMistakes(correct: String, answer: String): AnnotatedString {
    val mask = AnswerCheck.mistakeMask(answer, correct)
    return buildAnnotatedString {
        correct.forEachIndexed { i, ch ->
            if (mask[i]) withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(ch) } else append(ch)
        }
    }
}

/** Скелет слова с открытыми подсказкой буквами: «a c _ _ _ _ _   f _ _». */
/**
 * Подсказка «_ _ _»: открыто [revealed] букв. Подстановки (someone, one's) видны сразу —
 * так понятно, что на их месте можно написать и sb, и конкретное слово.
 */
private fun skeleton(term: String, revealed: Int): String {
    var shown = 0
    return term.split(' ').joinToString("   ") { word ->
        if (AnswerCheck.isPlaceholder(word)) word
        else word.map { ch ->
            when {
                !ch.isLetter() -> ch.toString()
                shown < revealed -> { shown++; ch.toString() }
                else -> { shown++; "_" }
            }
        }.joinToString(" ")
    }
}

/** Сколько букв можно открыть подсказками (без подстановок). */
private fun hintLetters(term: String) =
    term.split(' ').filterNot { AnswerCheck.isPlaceholder(it) }.sumOf { w -> w.count { it.isLetter() } }

/**
 * Подпись над правильным ответом под заданием. Сам вердикт («Верно / Неверно») показывает
 * нижняя панель [NextDock] — здесь он не повторяется.
 */
@Composable
internal fun AnswerLabel(verdict: Verdict) {
    when (verdict) {
        Verdict.Correct -> Unit
        Verdict.Typo -> Eyebrow("Почти — правильно так", color = MaterialTheme.colorScheme.tertiary)
        Verdict.Wrong -> Eyebrow("Правильный ответ", color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Rectangular proof mark; text and an icon explain the result independently of colour. */
@Composable
internal fun VerdictLine(verdict: Verdict) {
    val good = verdict != Verdict.Wrong
    val fg = if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (good) AlbionIcons.Check else AlbionIcons.Close, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Eyebrow(
            when (verdict) {
                Verdict.Correct -> "Верно"
                Verdict.Typo -> "Почти — опечатка"
                Verdict.Wrong -> "Неверно"
            },
            color = fg,
        )
    }
}

// ------------------------------------------------------------------ ввод ответа

/** Разбор ошибки: введённый вариант зачёркнут, правильное слово — крупно, на красноватой плашке. */
@Composable
private fun WrongAnswerFeedback(answer: String, correct: String) {
    // Две плашки: коралл — ваш вариант, мята — правильный; отличающиеся буквы подчёркнуты.
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteCard(tone = NoteTone.Bad) {
            Eyebrow("Ваш ответ", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(2.dp))
            Text(
                answer.ifBlank { "Ответ не дан" },
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (answer.isNotBlank()) TextDecoration.LineThrough else null),
                color = MaterialTheme.colorScheme.error,
            )
        }
        NoteCard(tone = NoteTone.Good) {
            Eyebrow("Правильный ответ", color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(2.dp))
            Text(if (answer.isNotBlank()) annotateMistakes(correct, answer) else AnnotatedString(correct),
                style = termStyle(correct, 34).copy(textAlign = TextAlign.Start), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun TypeAnswerRound(vm: GameViewModel, card: CardEntity) {
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val deck = Deck.byId(card.deck) ?: Deck.WORDS
    var input by rememberSaveable(card.id) { mutableStateOf("") }
    var verdict by rememberSaveable(card.id) { mutableStateOf<Verdict?>(null) }
    var hints by rememberSaveable(card.id) { mutableIntStateOf(0) }
    var showEn by rememberSaveable(card.id) { mutableStateOf(false) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val focus = remember(card.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val letters = hintLetters(card.term)
    val hinted = hints > 0 || showEn

    LaunchedEffect(card.id) { runCatching { focus.requestFocus() } }

    fun check(giveUp: Boolean = false) {
        if (verdict != null) return
        val v = if (giveUp) Verdict.Wrong else AnswerCheck.checkTerm(input, card.term)
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return }
        validation = null
        verdict = v
        if (v == Verdict.Wrong) shake++
        // Как в «Соберите предложение»: ответ с подсказкой не снимает слово из ошибок.
        val clean = v != Verdict.Wrong && !hinted
        vm.record(clean, if (clean) null else Mistake(card.meaningRu, if (v == Verdict.Wrong) input else "с подсказкой", card.term),
            verdict = v, hinted = hinted)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        audio.play(deck, card.audio, card.term, "term:${card.id}")
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(centered = true, label = "Переведите на английский") {
                if (!imeOpen()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (card.pos.isNotBlank()) Chip(card.pos)
                        LevelBadge(card.level)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    card.meaningRu,
                    style = if (imeOpen() || card.meaningRu.length > 90) MaterialTheme.typography.bodyLarge
                        else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (card.meaningRu.length > 80) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(showEn && verdict == null, enter = expandVertically() + fadeIn()) {
                    Text(
                        card.meaningEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (verdict == null) {
                    Spacer(Modifier.height(14.dp))
                    // Скелет слова — на мягкой плашке, открытые подсказкой буквы проступают на ней.
                    Text(
                        skeleton(card.term, hints),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, Capsule).padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            AnimatedVisibility(
                verdict != null,
                enter = fadeIn(tween(300)) + slideInVertically(tween(420)) { it / 3 },
            ) {
                val v = verdict ?: Verdict.Wrong
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (v == Verdict.Wrong) {
                        WrongAnswerFeedback(input, card.term)
                    } else {
                        // Вердикт — в нижней панели; здесь только подробности.
                        if (hinted) {
                            Spacer(Modifier.height(6.dp))
                            Text("С подсказкой — слово останется в ошибках", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        if (input.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Ваш ответ: $input", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (v == Verdict.Typo) annotateMistakes(card.term, input) else AnnotatedString(card.term),
                            style = termStyle(card.term, 36),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (card.ipa.isNotBlank()) {
                            Text(card.ipa, style = IpaStyle, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                        }
                        PracticeAudioButton(
                            playing = audio.playingKey == "term:${card.id}",
                            onClick = { audio.play(deck, card.audio, card.term, "term:${card.id}") },
                            size = 40.dp,
                        )
                    }
                    if (card.example1.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        NoteCard {
                            Eyebrow("Пример")
                            Spacer(Modifier.height(4.dp))
                            Text(card.example1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // Поле остаётся над кнопками и клавиатурой, даже если определение длинное.
        if (verdict == null) {
            AnswerField(
                value = input,
                onChange = { input = it },
                placeholder = "Ваш ответ",
                enabled = true,
                focus = focus,
                imeAction = ImeAction.Done,
                onIme = { check() },
                modifier = Modifier.padding(top = 10.dp).shakeOn(shake),
            )
        }
        com.marat.albion.ui.components.FieldError(validation)
        AnimatedContent(
            targetState = verdict != null,
            transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "actions",
            modifier = Modifier.padding(vertical = 12.dp),
        ) { answered ->
            if (answered) {
                NextDock(verdict != Verdict.Wrong, title = when { verdict == Verdict.Typo -> "Почти"; verdict == Verdict.Wrong -> "Неверно"; else -> "Верно" }) { vm.next() }
            } else {
                // Как во всех тренажёрах: «Проверить | Не знаю», подсказка — тихой ссылкой над ними.
                Column {
                    val canReveal = hints < letters - 1
                    if (canReveal || !showEn) com.marat.albion.ui.components.TextButton(
                        onClick = { if (canReveal) hints++ else showEn = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text(if (canReveal) "Подсказка: открыть букву" else "Подсказка: определение", color = MaterialTheme.colorScheme.primary) }
                    GameAnswerActions(onCheck = { check() }, onSecondary = { check(giveUp = true) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ неправильные глаголы

@Composable
fun IrregularRound(vm: GameViewModel, card: CardEntity) {
    val c = LocalAppContainer.current
    val audio = rememberCardAudio(c.audio)
    val data = remember(card.id) { vm.irregular(card) }
    var v2 by rememberSaveable(card.id) { mutableStateOf("") }
    var v3 by rememberSaveable(card.id) { mutableStateOf("") }
    var result by rememberSaveable(card.id) { mutableStateOf<Pair<Verdict, Verdict>?>(null) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    val focus2 = remember(card.id) { FocusRequester() }
    val focus3 = remember(card.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(card.id) { runCatching { focus2.requestFocus() } }

    val past = data?.forms?.getOrNull(1)?.variants.orEmpty()
    val participle = data?.forms?.getOrNull(2)?.variants.orEmpty()

    fun check(giveUp: Boolean = false) {
        if (result != null) return
        if (!giveUp && (v2.isBlank() || v3.isBlank())) {
            validation = if (v2.isBlank()) "Заполните Past Simple · V2." else "Заполните Past Participle · V3."
            if (v2.isBlank()) focus2.requestFocus() else focus3.requestFocus()
            shake++; return
        }
        validation = null
        val r = if (giveUp) Verdict.Wrong to Verdict.Wrong
            else AnswerCheck.checkForm(v2, past.map { it.text }) to AnswerCheck.checkForm(v3, participle.map { it.text })
        result = r
        val ok = r.first == Verdict.Correct && r.second == Verdict.Correct
        if (!ok) shake++
        vm.record(
            ok,
            Mistake(
                prompt = "${card.term} — ${card.meaningRu}",
                answer = listOf(v2, v3).joinToString(" · ") { it.ifBlank { "—" } },
                correct = past.joinToString(" / ") { it.text } + " · " + participle.joinToString(" / ") { it.text },
            ),
        )
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(centered = true, label = "Восстановите формы глагола") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("V1 · Infinitive", color = MaterialTheme.colorScheme.primary)
                    LevelBadge(card.level)
                }
                Spacer(Modifier.height(10.dp))
                Text(card.term, style = termStyle(card.term), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.ipa.isNotBlank()) {
                        Text(card.ipa, style = IpaStyle, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                    }
                    PracticeAudioButton(
                        playing = audio.playingKey == "term:${card.id}",
                        onClick = { audio.play(Deck.IRREGULAR, card.audio, card.term, "term:${card.id}") },
                        size = 40.dp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(card.meaningRu, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))

            Column(Modifier.shakeOn(shake), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                FormInput(
                    label = "V2 · Past Simple",
                    value = v2,
                    onChange = { if (result == null) v2 = it },
                    focus = focus2,
                    imeAction = ImeAction.Next,
                    onIme = { runCatching { focus3.requestFocus() } },
                    verdict = result?.first,
                    correct = past.map { it.text },
                    onPlay = { i -> audio.play(Deck.IRREGULAR, past[i].audio.firstOrNull(), past[i].text, "v2:${card.id}:$i") },
                    playingKey = audio.playingKey,
                    keyPrefix = "v2:${card.id}",
                )
                FormInput(
                    label = "V3 · Past Participle",
                    value = v3,
                    onChange = { if (result == null) v3 = it },
                    focus = focus3,
                    imeAction = ImeAction.Done,
                    onIme = { check() },
                    verdict = result?.second,
                    correct = participle.map { it.text },
                    onPlay = { i -> audio.play(Deck.IRREGULAR, participle[i].audio.firstOrNull(), participle[i].text, "v3:${card.id}:$i") },
                    playingKey = audio.playingKey,
                    keyPrefix = "v3:${card.id}",
                )
            }
        }
        com.marat.albion.ui.components.FieldError(validation)
        AnimatedContent(
            targetState = result != null,
            transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "actions",
            modifier = Modifier.padding(vertical = 12.dp),
        ) { answered ->
            if (answered) NextDock(result?.let { it.first == Verdict.Correct && it.second == Verdict.Correct }) { vm.next() }
            else GameAnswerActions(onCheck = { check() }, onSecondary = { check(giveUp = true) }, secondaryLabel = "Не знаю")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    focus: FocusRequester,
    imeAction: ImeAction,
    onIme: () -> Unit,
    verdict: Verdict?,
    correct: List<String>,
    onPlay: (Int) -> Unit,
    playingKey: String?,
    keyPrefix: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (verdict != null) VerdictLine(verdict)
        }
        AnswerField(
            value = value,
            onChange = onChange,
            placeholder = "Впишите форму",
            enabled = verdict == null,
            focus = focus,
            imeAction = imeAction,
            onIme = onIme,
            struck = verdict == Verdict.Wrong,
            accepted = verdict == Verdict.Correct,
        )
        AnimatedVisibility(verdict != null, enter = expandVertically() + fadeIn()) {
            // Вариантов может быть два (abided / abode) — на узком экране они переносятся.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                correct.forEachIndexed { i, text ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                        // Верная форма уже стоит в мятном поле — под ним только озвучка, без повтора слова.
                        val same = verdict == Verdict.Correct && text.equals(value.trim(), ignoreCase = true)
                        Text(
                            if (verdict == Verdict.Typo) annotateMistakes(text, value) else AnnotatedString(if (same) "Послушать" else text),
                            style = if (same) MaterialTheme.typography.labelLarge else MaterialTheme.typography.headlineSmall,
                            color = if (same) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(14.dp))
                        PracticeAudioButton(playing = playingKey == "$keyPrefix:$i", onClick = { onPlay(i) }, size = 32.dp, primary = false)
                    }
                }
            }
        }
    }
}
