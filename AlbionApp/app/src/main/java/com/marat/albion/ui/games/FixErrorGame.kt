package com.marat.albion.ui.games

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.AppContainer
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.games.FixError
import com.marat.albion.data.games.FixTask
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.settings.PracticeOptions
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.AlbionFonts
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** «Исправьте ошибку»: типичные ошибки русскоязычных вперемешку с неверными вариантами из grammar_tasks. */
class FixErrorViewModel(private val c: AppContainer) : ViewModel() {
    val game = Game.FIX_ERROR
    var phase by mutableStateOf(Phase.Loading)
        private set
    var count by mutableIntStateOf(10)
    var items by mutableStateOf<List<FixTask>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    val mistakes = mutableStateListOf<Mistake>()
    private val missed = mutableListOf<FixTask>()
    private var answered = false
    private var startedAt = 0L

    init {
        viewModelScope.launch {
            count = c.settings.practiceOptions(game.name, defaultCount = 10).first().count.takeIf { it in COUNTS } ?: 10
            phase = Phase.Setup
        }
    }

    fun saveOptions(markAsLast: Boolean = false) {
        val options = PracticeOptions(count = count)
        c.appScope.launch(Dispatchers.Main.immediate) { c.settings.savePracticeOptions(game.name, options, markAsLast) }
    }

    fun start(retry: List<FixTask>? = null) {
        if (retry == null) saveOptions(markAsLast = true)
        phase = Phase.Loading
        viewModelScope.launch {
            val pool = retry?.shuffled() ?: run {
                // Из учебника — сколько найдётся; типичных ошибок — не больше половины.
                val fromBook = try {
                    c.study.grammarPool("choice", "", count * 3).mapNotNull { FixError.fromGrammar(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("Albion", "Задания «Исправьте ошибку»", e)
                    emptyList()
                }
                val typical = FixError.typical.shuffled().take(maxOf(count / 2, count - fromBook.size))
                (typical + fromBook.take(count - typical.size)).shuffled()
            }
            items = pool
            index = 0
            correct = 0
            mistakes.clear()
            missed.clear()
            answered = false
            startedAt = SystemClock.elapsedRealtime()
            phase = if (pool.isEmpty()) Phase.Summary else Phase.Playing
        }
    }

    fun retryMistakes() = start(missed.toList())

    fun record(task: FixTask, ok: Boolean, answer: String) {
        if (phase != Phase.Playing || answered) return
        answered = true
        if (ok) correct++ else {
            mistakes += Mistake(task.sentence, answer.ifBlank { "—" }, task.correctSentence)
            missed += task
        }
        val duration = SystemClock.elapsedRealtime() - startedAt
        c.appScope.launch { c.study.recordAnswer(game.name, task.id, ok, duration) }
    }

    fun next() {
        if (phase != Phase.Playing || !answered) return
        if (index + 1 >= items.size) phase = Phase.Summary else index++
        answered = false
        startedAt = SystemClock.elapsedRealtime()
    }

    fun backToSetup() { phase = Phase.Setup }

    companion object {
        val COUNTS = listOf(10, 20, 30)
    }
}

@Composable
fun FixErrorHost(navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "fix-error") { FixErrorViewModel(c) }
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    BackHandler(enabled = vm.phase == Phase.Summary) { vm.backToSetup() }
    AnimatedContent(targetState = vm.phase, transitionSpec = { practiceTransition(reduced) }, label = "phase") { phase ->
        when (phase) {
            Phase.Setup -> GameSetup(
                game = vm.game, deck = com.marat.albion.data.content.Deck.WORDS, count = vm.count,
                onDeck = {}, onCount = { vm.count = it; vm.saveOptions() }, onStart = { vm.start() }, onBack = navigator::back,
                counts = FixErrorViewModel.COUNTS,
            )
            Phase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                com.marat.albion.ui.components.LoadingState("Готовим упражнение")
            }
            Phase.Summary -> GameSummary(
                game = vm.game, correct = vm.correct, total = vm.items.size, mistakes = vm.mistakes.toList(),
                onRestart = { vm.start() }, onRetryMistakes = { vm.retryMistakes() }, onDone = navigator::back,
                scoreLabel = "найдено и исправлено",
            )
            Phase.Playing -> Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                val leave = rememberLeaveGuard(active = vm.index > 0) { vm.backToSetup() }
                GameTopRow(vm.game, vm.index, vm.items.size, onClose = leave)
                AnimatedContent(targetState = vm.index, transitionSpec = { practiceTransition(reduced) },
                    modifier = Modifier.weight(1f), label = "task") { i ->
                    vm.items.getOrNull(i)?.let { FixErrorRound(vm, it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixErrorRound(vm: FixErrorViewModel, task: FixTask) {
    val tokens = remember(task.id) { task.tokens }
    var missedTaps by rememberSaveable(task.id) { mutableStateOf(emptyList<Int>()) }
    var found by rememberSaveable(task.id) { mutableStateOf(false) }
    var input by rememberSaveable(task.id) { mutableStateOf("") }
    var verdict by rememberSaveable(task.id) { mutableStateOf<Verdict?>(null) }
    var shake by remember(task.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    val focus = remember(task.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(found) { if (found && verdict == null) runCatching { focus.requestFocus() } }

    fun check(giveUp: Boolean) {
        if (verdict != null) return
        if (!giveUp && input.isBlank()) { validation = "Впишите исправление или выберите «Не знаю»."; shake++; return }
        validation = null
        val v = if (giveUp) Verdict.Wrong else task.check(input)
        verdict = v
        found = true
        if (v == Verdict.Wrong) shake++
        // Чисто — если ошибку нашли с первого касания и исправили верно.
        vm.record(task, v == Verdict.Correct && missedTaps.isEmpty(), input)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(centered = true, label = if (found) "Как правильно?" else "Коснитесь слова с ошибкой") {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tokens.forEachIndexed { i, (word, range) ->
                        val isError = task.isError(range)
                        val (fg, border) = when {
                            found && isError -> scheme.error to scheme.error
                            i in missedTaps -> scheme.onSurfaceVariant to scheme.outlineVariant
                            else -> scheme.onSurface to scheme.outlineVariant
                        }
                        Text(
                            word,
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = AlbionFonts.Reading),
                            color = fg,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .border(if (found && isError) 2.dp else .75.dp, border, RectangleShape)
                                .clickable(enabled = !found && i !in missedTaps, role = Role.Button) {
                                    if (isError) found = true else { missedTaps = missedTaps + i; shake++ }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (missedTaps.isNotEmpty() && !found) {
                    Spacer(Modifier.height(10.dp))
                    Text("Здесь всё верно — ищите дальше.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(verdict != null, enter = fadeIn(tween(300)) + slideInVertically(tween(420)) { it / 3 }) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    NoteCard(tone = NoteTone.Good) {
                        Eyebrow("Правильно", color = scheme.tertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(task.correctSentence, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AlbionFonts.Reading),
                            color = scheme.onSurface)
                    }
                    if (task.explanation.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        NoteCard {
                            Eyebrow("Почему")
                            Spacer(Modifier.height(4.dp))
                            Text(task.explanation, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        }
                    }
                }
            }
        }
        if (found && verdict == null) {
            AnswerField(
                value = input, onChange = { input = it }, placeholder = "Вместо «${task.wrongText}»", enabled = true, focus = focus,
                imeAction = ImeAction.Done, onIme = { check(false) }, modifier = Modifier.padding(top = 10.dp).shakeOn(shake),
            )
        }
        com.marat.albion.ui.components.FieldError(validation)
        Box(Modifier.padding(vertical = 12.dp)) {
            when {
                verdict != null -> NextDock(verdict == Verdict.Correct && missedTaps.isEmpty(),
                    title = if (verdict == Verdict.Correct) "Исправлено" else "Неверно") { vm.next() }
                found -> GameAnswerActions(onCheck = { check(false) }, onSecondary = { check(true) })
                else -> com.marat.albion.ui.components.TextButton(onClick = { check(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Не вижу ошибки — показать", textAlign = TextAlign.Center, color = scheme.primary)
                }
            }
        }
    }
}
