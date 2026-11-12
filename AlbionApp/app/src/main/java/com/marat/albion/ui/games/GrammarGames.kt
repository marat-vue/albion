package com.marat.albion.ui.games

import com.marat.albion.ui.components.QuietCircularProgressIndicator as CircularProgressIndicator
import com.marat.albion.ui.theme.controlBorder
import com.marat.albion.ui.theme.pressScale
import com.marat.albion.ui.components.AlbionIcons
import androidx.compose.ui.draw.clip
import com.marat.albion.ui.components.gutter
import android.os.SystemClock

import androidx.activity.compose.BackHandler
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.AppContainer
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.grammar.TopicKind
import com.marat.albion.data.grammar.GrammarPrompt
import com.marat.albion.data.grammar.GrammarQuizSession
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.FeatureHelp
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.TopicRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.marat.albion.data.settings.PracticeOptions

private val LEVELS = listOf("", "A1", "A2", "B1", "B2", "C1")

/**
 * Состояние грамматической игры: настройка → задания → итог.
 * С [topic] — режим «Проверь себя»: смешанная выборка из банка темы, без настройки.
 */
class GrammarGameViewModel(
    private val c: AppContainer,
    val game: Game?,
    val topic: Int? = null,
    private val topicId: String? = null,
) : ViewModel() {
    val kind = if (game == Game.GRAMMAR_GAP) "input" else "choice"
    var phase by mutableStateOf(Phase.Loading)
        private set
    var level by mutableStateOf("")
    var count by mutableIntStateOf(10)
    var items by mutableStateOf<List<GrammarTaskEntity>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    /** Вопросы с «Мой ответ тоже верен»: не входят ни в верные, ни в итог. */
    var excluded by mutableIntStateOf(0)
        private set
    val mistakes = mutableStateListOf<Mistake>()
    private val missed = mutableListOf<GrammarTaskEntity>()
    private var session: GrammarQuizSession? = null
    private var questionStartedAt = 0L
    var retryingMistakes by mutableStateOf(false)
        private set
    /** Номер темы → id файла учебника (для ссылки «Правило»). */
    var topicIds by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            topicIds = c.grammar.book().topics
                .filter { it.kind == TopicKind.Lesson && it.number != null }
                .associate { it.number!! to it.id }
            if (topic == null) {
                game?.let {
                    val saved = c.settings.practiceOptions(it.name).first()
                    level = saved.level.takeIf { lv -> lv in LEVELS }.orEmpty()
                    count = saved.count.takeIf { n -> n in listOf(10, 20, 30) } ?: 10
                }
                phase = Phase.Setup
            }
        }
        if (topic != null) start()
    }

    fun start(retry: List<GrammarTaskEntity>? = null) {
        if (retry == null) saveOptions(markAsLast = true)
        phase = Phase.Loading
        viewModelScope.launch {
            val pool = retry?.shuffled() ?: if (topic != null) c.study.topicPool(topic) else c.study.grammarPool(kind, level, count)
            items = pool
            retryingMistakes = retry != null
            session = GrammarQuizSession(pool.map { it.id }, fullAttempt = topic != null && !retryingMistakes)
            index = 0
            correct = 0
            excluded = 0
            mistakes.clear()
            missed.clear()
            questionStartedAt = SystemClock.elapsedRealtime()
            phase = if (pool.isEmpty()) Phase.Summary else Phase.Playing
        }
    }

    fun retryMistakes() = start(missed.toList())

    fun saveOptions(markAsLast: Boolean = false) {
        if (topic != null || game == null) return
        val options = PracticeOptions(count = count, level = level)
        c.appScope.launch(Dispatchers.Main.immediate) { c.settings.savePracticeOptions(game.name, options, markAsLast) }
    }

    private val shuffled = HashMap<String, List<String>>()

    /** Порядок вариантов фиксируется на всю игру — не меняется после перехода к правилу. */
    fun optionsFor(task: GrammarTaskEntity) = shuffled.getOrPut(task.id) { task.optionList.shuffled() }

    fun record(task: GrammarTaskEntity, ok: Boolean, answer: String) {
        if (phase != Phase.Playing || items.getOrNull(index)?.id != task.id || session?.record(task.id, ok) != true) return
        if (ok) correct++ else {
            mistakes += Mistake(task.prompt, answer, task.correctAnswerText)
            missed += task
        }
        val duration = SystemClock.elapsedRealtime() - questionStartedAt
        val answeredAt = System.currentTimeMillis()
        c.appScope.launch { c.study.recordGrammar(task.id, ok, task.kind, duration, answeredAt) }
    }

    fun isAnswered(task: GrammarTaskEntity): Boolean = session?.answered(task.id) == true

    /**
     * «Мой ответ тоже верен»: ошибка не записывается, вопрос выходит из результата,
     * а ответ попадает в журнал для пополнения альтернатив в grammar_tasks.xlsx.
     */
    fun acceptOwnAnswer(task: GrammarTaskEntity, answer: String) {
        if (phase != Phase.Playing || items.getOrNull(index)?.id != task.id || session?.exclude(task.id) != true) return
        excluded++
        c.appScope.launch { c.settings.addGrammarOwnAnswer(task.id, answer) }
    }

    fun next() {
        if (phase != Phase.Playing || items.getOrNull(index)?.let { session?.answered(it.id) } != true) return
        if (index + 1 < items.size) { index++; questionStartedAt = SystemClock.elapsedRealtime(); return }
        phase = Phase.Summary
        val result = session?.result()
        if (topicId != null && result != null && result.updatesMastery) {
            // Сохраняем и неуспешный полный тест; повтор ошибок результат не заменяет.
            c.appScope.launch { c.grammar.recordQuiz(topicId, result) }
        }
    }

    fun backToSetup() {
        phase = Phase.Setup
    }
}

@Composable
fun GrammarGameHost(game: Game, navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "grammar-${game.name}") { GrammarGameViewModel(c, game) }
    val total by remember(vm.kind) { c.study.observeGrammarTaskCount(vm.kind) }.collectAsStateWithLifecycle(-1)
    val openRule: (Int) -> Unit = { n -> vm.topicIds[n]?.let { navigator.open(TopicRoute(it)) } }

    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    BackHandler(enabled = vm.phase == Phase.Summary) { vm.backToSetup() }

    AnimatedContent(
        targetState = vm.phase,
        transitionSpec = { practiceTransition(reduced) },
        label = "phase",
    ) { phase ->
        when (phase) {
            Phase.Setup -> GrammarSetup(vm, total, onBack = navigator::back)
            Phase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Phase.Summary -> GameSummary(
                game = vm.game,
                correct = vm.correct,
                total = vm.items.size - vm.excluded,
                mistakes = vm.mistakes.toList(),
                onRestart = { vm.start() },
                onRetryMistakes = { vm.retryMistakes() },
                onDone = navigator::back,
            )
            Phase.Playing -> Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                com.marat.albion.ui.theme.AnswerHaptics(vm.correct, vm.mistakes.size)
                val leave = rememberLeaveGuard(active = vm.index > 0) { vm.backToSetup() }
                GameTopRow(vm.game, vm.index, vm.items.size, onClose = leave)
                AnimatedContent(
                    targetState = vm.index,
                    transitionSpec = { practiceTransition(reduced) },
                    modifier = Modifier.weight(1f),
                    label = "task",
                ) { i ->
                    val task = vm.items.getOrNull(i) ?: return@AnimatedContent
                    TaskRound(vm, task, openRule)
                }
            }
        }
    }
}

/** Вид задания: в обычной игре — по игре, в «Проверь себя» — по самому заданию. */
@Composable
private fun TaskRound(vm: GrammarGameViewModel, task: GrammarTaskEntity, openRule: (Int) -> Unit) {
    val gap = if (vm.topic != null) task.kind == "input" else vm.game == Game.GRAMMAR_GAP
    if (gap) GapRound(vm, task, openRule) else ChoiceRound(vm, task, openRule)
}

/** «Проверь себя» в конце темы: сразу задания, без экрана настройки. */
@Composable
fun TopicQuizHost(topic: Int, topicId: String, title: String, navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "quiz-$topicId") { GrammarGameViewModel(c, null, topic, topicId) }
    // Отдельная запись в стеке сохраняет текущий тест, даже если тема уже открыта под ним.
    val openRule: (Int) -> Unit = { n ->
        vm.topicIds[n]?.let { navigator.open(TopicRoute(it, fromQuiz = true)) }
    }
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    val quizTitle = if (vm.retryingMistakes) "Повтор ошибок · тема $topic" else "Проверьте себя · тема $topic"

    AnimatedContent(
        targetState = vm.phase,
        transitionSpec = { practiceTransition(reduced) },
        label = "quizPhase",
    ) { phase ->
        when (phase) {
            Phase.Setup, Phase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Phase.Summary -> GameSummary(
                game = null,
                title = if (vm.retryingMistakes) "$title · повтор ошибок" else title,
                correct = vm.correct,
                total = vm.items.size - vm.excluded,
                mistakes = vm.mistakes.toList(),
                onRestart = { vm.start() },
                onRetryMistakes = { vm.retryMistakes() },
                onDone = navigator::back,
                restartLabel = "Пройти ещё раз",
            )
            Phase.Playing -> Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                com.marat.albion.ui.theme.AnswerHaptics(vm.correct, vm.mistakes.size)
                val leave = rememberLeaveGuard(active = vm.index > 0, onLeave = navigator::back)
                GameTopRow(null, vm.index, vm.items.size, onClose = leave, title = quizTitle)
                AnimatedContent(
                    targetState = vm.index,
                    transitionSpec = { practiceTransition(reduced) },
                    modifier = Modifier.weight(1f),
                    label = "quizTask",
                ) { i ->
                    val task = vm.items.getOrNull(i) ?: return@AnimatedContent
                    TaskRound(vm, task, openRule)
                }
            }
        }
    }
}

@Composable
private fun GrammarSetup(vm: GrammarGameViewModel, total: Int, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack = onBack) {
            FeatureHelp("practice.${vm.game!!.name}", "Как заниматься", vm.game.description)
        }
        SetupBody(
            start = if (total == 0) null else ({
                PrimaryButton("Начать", onClick = { vm.start() }, trailingIcon = AlbionIcons.ArrowForward,
                    modifier = Modifier)
            }),
        ) {
            GameSetupHeader(vm.game!!, Modifier) {
                if (total == 0) {
                    Text(
                        "Для этого упражнения пока нет заданий. Попробуйте другой режим практики.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                    )
                    return@GameSetupHeader
                }
                PracticeSectionLabel("01", "Уровень · $total заданий")
                com.marat.albion.ui.components.OptionPicker("Уровень заданий", LEVELS.map { it.ifEmpty { "Все уровни" } },
                    LEVELS.indexOf(vm.level), { vm.level = LEVELS[it]; vm.saveOptions() })
                PracticeSectionLabel("02", "Длина")
                val lengths = listOf(10, 20, 30)
                com.marat.albion.ui.components.SegmentedPicker(lengths.map { "$it" }, lengths.indexOf(vm.count).coerceAtLeast(0),
                    { vm.count = lengths[it]; vm.saveOptions() })
            }
        }
    }
}

/**
 * Пустой пропуск — видимые символы, а не подчёркнутые пробелы: пробелы в конце строки
 * при переносе не рисуются, и пропуск «исчезал» («It rained.» вместо «It ___ rained.»).
 */
internal const val GAP_MARK = "______"

/** Каждая часть ответа вставляется только в соответствующий пропуск. */
internal fun promptWithGap(prompt: String, filled: String?): AnnotatedString = buildAnnotatedString {
    val parsed = GrammarPrompt(prompt)
    val values = filled?.let(parsed::answerParts)
    parsed.parts.indices.forEach { i ->
        append(parsed.textPart(i, values?.getOrNull(i)))
        if (i < parsed.gapCount) {
            val value = values?.get(i)
            if (value == null) {
                append(GAP_MARK)
            } else {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(value) }
            }
        }
    }
}

@Composable
private fun TaskHeader(task: GrammarTaskEntity, showTopic: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // В смешанной тренировке название темы до ответа — готовая подсказка («Артикли a и an»), поэтому его видно только после.
        if (showTopic) {
            Eyebrow(listOfNotNull(task.topic?.let { "Тема $it" }, task.topicTitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.primary, maxLines = 2)
            Spacer(Modifier.width(10.dp))
        }
        LevelBadge(task.level)
    }
}

/** Пояснение и ссылка на правило после ответа. */
@Composable
private fun Explanation(task: GrammarTaskEntity, openRule: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (task.explanation.isNotBlank()) {
            NoteCard {
                Eyebrow("Почему так", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(task.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        task.topic?.let { n ->

            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                    // Без фокуса: после «Готово» на клавиатуре ссылка не должна подсвечиваться.
                    .clickable { openRule(n) }
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlbionIcons.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Правило · тема $n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Icon(AlbionIcons.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ выбор варианта

@Composable
private fun ChoiceRound(vm: GrammarGameViewModel, task: GrammarTaskEntity, openRule: (Int) -> Unit) {
    val options = remember(task.id) { vm.optionsFor(task) }
    var picked by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var shake by remember(task.id) { mutableIntStateOf(0) }
    val answered = picked != null

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp)) {
            TaskCard(Modifier.shakeOn(shake), label = "ВЫБЕРИТЕ ВЕРНЫЙ ВАРИАНТ") {
                TaskHeader(task, showTopic = answered || vm.topic != null)
                Spacer(Modifier.height(14.dp))
                Text(
                    promptWithGap(task.prompt, if (answered && task.prompt.contains("___")) task.answer else null),
                    style = promptStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEachIndexed { index, option ->
                    OptionButton(
                        text = option,
                        index = index,
                        state = when {
                            !answered -> OptionState.Idle
                            option == task.answer -> OptionState.Correct
                            option == picked -> OptionState.Wrong
                            else -> OptionState.Dim
                        },
                        onClick = {
                            if (!answered) {
                                picked = option
                                val ok = option == task.answer
                                if (!ok) shake++
                                vm.record(task, ok, option)
                            }
                        },
                    )
                }
            }
            AnimatedVisibility(answered, enter = fadeIn(tween(300)) + expandVertically()) {
                Explanation(task, openRule)
            }
        }
        AnimatedVisibility(answered, enter = fadeIn(tween(240)) + slideInVertically { it / 2 }) {
            Box(Modifier.padding(vertical = 12.dp)) { NextDock(picked == task.answer) { vm.next() } }
        }
    }
}

internal enum class OptionState { Idle, Correct, Wrong, Dim }

/** An answer slip with a readable check/cross and a restrained state change. */
@Composable
internal fun OptionButton(text: String, state: OptionState, index: Int? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        when (state) {
            // Мята — «верно», ирис остаётся цветом выбора и действий.
            OptionState.Correct -> MaterialTheme.colorScheme.tertiaryContainer
            OptionState.Wrong -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        tween(260), label = "bg",
    )
    val fg by animateColorAsState(
        when (state) {
            OptionState.Correct -> MaterialTheme.colorScheme.onTertiaryContainer
            OptionState.Wrong -> MaterialTheme.colorScheme.error
            OptionState.Dim -> MaterialTheme.colorScheme.onSurfaceVariant
            OptionState.Idle -> MaterialTheme.colorScheme.onSurface
        },
        tween(260), label = "fg",
    )
    val border by animateColorAsState(
        when (state) {
            OptionState.Correct -> MaterialTheme.colorScheme.tertiary
            OptionState.Wrong -> MaterialTheme.colorScheme.error
            OptionState.Dim -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
            OptionState.Idle -> MaterialTheme.colorScheme.controlBorder
        },
        tween(260), label = "border",
    )
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(state, reduced) {
        if (reduced) pop.snapTo(1f)
        if (state == OptionState.Correct && !reduced) {
            pop.snapTo(.985f)
            pop.animateTo(1f, com.marat.albion.ui.theme.Springs.bouncy())
        }
    }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .graphicsLayer { scaleX = if (reduced) 1f else pop.value; scaleY = if (reduced) 1f else pop.value }
            .pressScale(interaction, 0.97f)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .clip(shape)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = state == OptionState.Idle, onClick = onClick)
            .semantics { stateDescription = when (state) {
                OptionState.Correct -> "Верный ответ"
                OptionState.Wrong -> "Неверный ответ"
                OptionState.Dim -> "Другой вариант"
                OptionState.Idle -> "Вариант ответа"
            } }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).border(.75.dp, fg.copy(alpha = .3f), MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
            when (state) {
                OptionState.Correct -> Icon(AlbionIcons.Check, null, tint = fg, modifier = Modifier.size(16.dp))
                OptionState.Wrong -> Icon(AlbionIcons.Close, null, tint = fg, modifier = Modifier.size(16.dp))
                else -> Text(index?.let { ('A'.code + it).toChar().toString() } ?: "—",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = com.marat.albion.ui.theme.AlbionFonts.Tech), color = fg)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (state == OptionState.Wrong) TextDecoration.LineThrough else null),
            color = fg,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ допиши пропуск

@Composable
private fun GapRound(vm: GrammarGameViewModel, task: GrammarTaskEntity, openRule: (Int) -> Unit) {
    var input by rememberSaveable(task.id) { mutableStateOf("") }
    var verdict by rememberSaveable(task.id) { mutableStateOf<Verdict?>(null) }
    var shake by remember(task.id) { mutableIntStateOf(0) }
    var validation by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var gaveUp by rememberSaveable(task.id) { mutableStateOf(false) }
    var claimed by rememberSaveable(task.id) { mutableStateOf(false) }
    val focus = remember(task.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val accepted = task.acceptedAnswers
    val parsed = remember(task.prompt) { GrammarPrompt(task.prompt) }
    val submitted = parsed.answerParts(input)?.joinToString(" / ") ?: input
    // Предложение целиком можно сказать по-разному: неверный ответ записывается только по «Дальше»,
    // чтобы до этого его можно было отметить «Мой ответ тоже верен».
    val canClaim = parsed.gapCount == 0 && verdict == Verdict.Wrong && !gaveUp && !claimed

    LaunchedEffect(task.id) { runCatching { focus.requestFocus() } }

    fun check(giveUp: Boolean = false) {
        if (verdict != null) return
        if (!giveUp && input.isBlank()) { validation = "Введите ответ или выберите «Не знаю»."; shake++; return }
        validation = null
        val v = if (giveUp) Verdict.Wrong else AnswerCheck.checkGrammar(submitted, accepted, task.requiresExactWriting, task.commasMatter)
        verdict = v
        gaveUp = giveUp
        if (v == Verdict.Wrong) shake++
        if (v == Verdict.Correct || giveUp || parsed.gapCount > 0) vm.record(task, v == Verdict.Correct, input)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    val filled = when (verdict) {
        null -> null
        Verdict.Correct -> accepted.firstOrNull {
            AnswerCheck.normalizeGrammar(it, task.requiresExactWriting, task.commasMatter) ==
                AnswerCheck.normalizeGrammar(submitted, task.requiresExactWriting, task.commasMatter)
        } ?: input.trim()
        else -> accepted.firstOrNull() ?: task.answer
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp)) {
            TaskCard(label = if (parsed.gapCount > 0) "ЗАПОЛНИТЕ ПРОПУСК" else "НАПИШИТЕ ОТВЕТ") {
                TaskHeader(task, showTopic = verdict != null || vm.topic != null)
                Spacer(Modifier.height(14.dp))
                Text(
                    promptWithGap(task.prompt, filled),
                    style = promptStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(20.dp))
            AnswerField(
                value = input,
                onChange = { if (verdict == null) input = it },
                placeholder = when {
                    parsed.gapCount > 1 -> "Ответы по порядку через /"
                    parsed.gapCount == 1 -> "Впишите пропуск"
                    else -> "Напишите ответ целиком"
                },
                enabled = verdict == null,
                focus = focus,
                imeAction = ImeAction.Done,
                onIme = { check() },
                modifier = Modifier.shakeOn(shake),
                struck = verdict == Verdict.Wrong, accepted = verdict == Verdict.Correct,
            )
            AnimatedVisibility(verdict != null, enter = fadeIn(tween(300)) + expandVertically()) {
                val v = verdict ?: Verdict.Wrong
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    AnswerLabel(v)
                    if (v != Verdict.Correct) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            AnnotatedString(accepted.first()),
                            style = promptStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (accepted.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("Также верно: " + accepted.drop(1).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canClaim) {
                        com.marat.albion.ui.components.GhostButton(
                            "Мой ответ тоже верен",
                            onClick = { vm.acceptOwnAnswer(task, input); claimed = vm.isAnswered(task) },
                            modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                        )
                    }
                    if (claimed) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Ошибка не записана, вопрос не входит в результат. Ответ сохранён в «Настройки → Материалы и данные».",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Explanation(task, openRule)
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
                NextDock(if (claimed) null else verdict != Verdict.Wrong, title = if (claimed) "Ответ принят" else null) {
                    if (verdict == Verdict.Wrong && !vm.isAnswered(task)) vm.record(task, false, input)
                    vm.next()
                }
            } else {
                GameAnswerActions(onCheck = { check() }, onSecondary = { check(giveUp = true) })
            }
        }
    }
}
