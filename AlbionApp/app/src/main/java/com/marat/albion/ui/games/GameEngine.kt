package com.marat.albion.ui.games

import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.map

import android.os.SystemClock

import androidx.activity.compose.BackHandler
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.AppContainer
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.settings.PracticeOptions
import com.marat.albion.data.games.SentencePuzzle
import com.marat.albion.data.games.TenseDrill
import com.marat.albion.data.games.TenseTask
import com.marat.albion.ui.nav.Navigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/** Короткие служебные слова: отдельно их и не произносят, и не распознают. */
private val FUNCTION_WORDS = setOf("the", "and", "but", "for", "nor", "yet", "its", "his", "her", "our", "you", "him", "she", "they", "them", "who", "was", "are", "has", "had", "did", "not", "off", "out", "per", "via")

enum class Phase { Setup, Loading, Playing, Summary }

/**
 * Общее состояние мини-игры: настройка → вопросы → итог. На FSRS влияют только игры с [Game.gradesMemory]:
 * их ответ на подошедшее повторение засчитывается автоматически (см. [com.marat.albion.data.repo.AutoGrade]).
 */
class GameViewModel(private val c: AppContainer, val game: Game,
    private val initialDeck: Deck? = null, private val focusMistakes: Boolean = false, private val initialCount: Int? = null,
) : ViewModel() {
    var phase by mutableStateOf(Phase.Loading)
        private set
    var deck by mutableStateOf(game.decks.first())
    var count by mutableIntStateOf(if (game == Game.IRREGULAR) 20 else 10)
    /** Режим игры (для «Тренажёра времён»: 0 — выбор, 1 — ввод). */
    var mode by mutableIntStateOf(0)
    /** Играть только слова, на которых вы ошибались в этой игре. */
    var onlyMistakes by mutableStateOf(false)
    private val needAudio get() = game == Game.SHADOWING || game == Game.PRONUNCIATION || game == Game.MINIMAL_PAIRS

    /** Сколько ошибок накоплено в выбранной колоде — для экрана настройки. */
    fun observeAvailable(deck: com.marat.albion.data.content.Deck) = c.study.observeWordCounts().map { it[deck.id] ?: 0 }
    fun observeMistakes(deck: com.marat.albion.data.content.Deck) = c.study.observeGameMistakes(deck, game.name, needAudio)
    var items by mutableStateOf<List<CardEntity>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    /** Сколько ответов этой тренировки засчитано в повторения FSRS. */
    var graded by mutableIntStateOf(0)
        private set
    val mistakes = mutableStateListOf<Mistake>()
    private val missed = mutableListOf<CardEntity>()
    private var answered = false
    private var questionStartedAt = 0L

    init {
        viewModelScope.launch {
            val saved = c.settings.practiceOptions(game.name, defaultCount = count).first()
            deck = game.decks.firstOrNull { it.id == saved.deckId } ?: game.decks.first()
            val counts = if (game == Game.IRREGULAR) listOf(10, 20, Int.MAX_VALUE) else listOf(10, 20, 30)
            count = saved.count.takeIf { it in counts } ?: 10
            mode = saved.mode.coerceIn(0, (game.modes.size - 1).coerceAtLeast(0))
            initialDeck?.takeIf { it in game.decks }?.let { deck = it }
            initialCount?.takeIf { it in counts }?.let { count = it }
            onlyMistakes = focusMistakes
            phase = Phase.Setup
        }
    }

    fun saveOptions(markAsLast: Boolean = false) {
        val options = PracticeOptions(deckId = deck.id, count = count, mode = mode)
        c.appScope.launch(Dispatchers.Main.immediate) { c.settings.savePracticeOptions(game.name, options, markAsLast) }
    }

    fun start(retry: List<CardEntity>? = null) {
        if (retry == null) saveOptions(markAsLast = true)
        phase = Phase.Loading
        // Новая игра — новые примеры; повтор ошибок — те же задания.
        if (retry == null) { clozes.clear(); tenses.clear(); tenseOptions.clear(); puzzles.clear(); shuffledTokens.clear(); sentenceTranslations.clear(); pairTasks.clear(); pairCards.clear() }
        translating?.cancel()
        // Фоновый перевод доделывает текущее предложение (обрыв посреди генерации терял работу) и останавливается.
        stopUpcoming = true
        viewModelScope.launch {
            val pool = retry?.shuffled() ?: when (game) {
                // Нужны карточки, у которых есть пример подходящей длины.
                Game.SENTENCE -> c.study.gamePool(deck, game.name, (count * 4).coerceAtMost(500), onlyMistakes = onlyMistakes).filter { puzzle(it) != null }.take(count)
                Game.TENSES -> c.study.gamePool(deck, game.name, (count * 2).coerceAtMost(500), onlyMistakes = onlyMistakes).filter { tense(it) != null }.take(count)
                Game.CLOZE -> c.study.gamePool(deck, game.name, (count * 4).coerceAtMost(500), onlyMistakes = onlyMistakes).filter { cloze(it) != null }.take(count)
                // Речь: служебные слова вроде «a» или «to» распознавание не узнаёт отдельно — их пропускаем.
                Game.MINIMAL_PAIRS -> minimalPairs()
                Game.SHADOWING, Game.PRONUNCIATION -> c.study.gamePool(deck, game.name, (count * 2).coerceAtMost(500), withAudio = true, onlyMistakes = onlyMistakes)
                    .filter { speakable(it.term) }.take(count)
                else -> c.study.gamePool(deck, game.name, count.coerceAtMost(500), withAudio = needAudio, onlyMistakes = onlyMistakes)
            }
            items = pool
            index = 0
            correct = 0
            graded = 0
            mistakes.clear()
            missed.clear()
            answered = false
            questionStartedAt = SystemClock.elapsedRealtime()
            phase = if (pool.isEmpty()) Phase.Summary else Phase.Playing
        }
    }

    fun retryMistakes() = start(missed.toList())

    /** Пары словаря (id словаря → пары): поиск по транскрипциям тяжёлый, считаем один раз. */
    private var pairs: Pair<String, List<com.marat.albion.data.games.MinimalPair>>? = null
    private val pairCards = HashMap<String, CardEntity>()
    private val pairTasks = HashMap<String, com.marat.albion.data.games.PairTask>()

    /** Задание «Минимальных пар» для карточки, которая звучит в раунде. */
    fun pairTask(card: CardEntity): com.marat.albion.data.games.PairTask? = pairTasks[card.id]

    /** Карточка слова из пары — её озвучка и транскрипция. */
    fun pairCard(id: String): CardEntity? = pairCards[id]

    /**
     * Пары по транскрипциям всех слов с озвучкой (считаются один раз за экран); слова с ошибками — первыми.
     * Раунд — карточка звучащего слова.
     */
    private suspend fun minimalPairs(): List<CardEntity> {
        val all = c.study.gamePool(deck, game.name, Int.MAX_VALUE, withAudio = true)
        val found = pairs?.takeIf { it.first == deck.id }?.second ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
            com.marat.albion.data.games.MinimalPairs.find(all.map { com.marat.albion.data.games.PairWord(it.id, it.term, it.ipa) })
        }.also { pairs = deck.id to it }
        all.forEach { pairCards[it.id] = it }
        val order = if (onlyMistakes) c.study.gamePool(deck, game.name, Int.MAX_VALUE, withAudio = true, onlyMistakes = true).map { it.id }
            else all.map { it.id }
        val tasks = com.marat.albion.data.games.MinimalPairs.session(found, order, count)
        tasks.forEach { pairTasks[it.target.id] = it }
        return tasks.mapNotNull { pairCards[it.target.id] }
    }

    private fun speakable(term: String): Boolean {
        val word = term.trim().lowercase()
        return word.count(Char::isLetter) >= 3 && word !in FUNCTION_WORDS
    }

    /**
     * Засчитать текущий вопрос. [verdict] и [hinted] — результат проверки ввода: по ним подошедшее
     * повторение слова получает оценку FSRS, если игра проверяет вспоминание ([Game.gradesMemory]).
     */
    fun record(ok: Boolean, mistake: Mistake? = null, verdict: com.marat.albion.data.games.Verdict? = null, hinted: Boolean = false) {
        if (phase != Phase.Playing || answered) return
        val card = items.getOrNull(index) ?: return
        answered = true
        val duration = SystemClock.elapsedRealtime() - questionStartedAt
        val answeredAt = System.currentTimeMillis()
        if (ok) correct++ else {
            mistake?.let { mistakes += it }
            missed += card
        }
        // Запоминаем и между играми: ошибки попадутся первыми в следующий раз.
        val grade = verdict?.takeIf { game.gradesMemory }
        c.appScope.launch {
            c.study.recordGame(card.id, game.name, ok, duration, answeredAt)
            if (grade != null) {
                val rating = c.study.gradeFromPractice(card.id, grade, hinted,
                    com.marat.albion.data.repo.ReviewSource.game(game.name), duration, answeredAt)
                if (rating != null) kotlinx.coroutines.withContext(Dispatchers.Main) { graded++ }
            }
        }
    }

    fun next() {
        if (phase != Phase.Playing || !answered) return
        c.audio.stop()
        if (index + 1 >= items.size) phase = Phase.Summary else index++
        answered = false
        questionStartedAt = SystemClock.elapsedRealtime()
    }

    fun backToSetup() {
        c.audio.stop()
        phase = Phase.Setup
    }

    fun irregular(card: CardEntity) = c.study.irregular(card)

    private val puzzles = HashMap<String, SentencePuzzle?>()

    /** Предложение карточки для «Соберите предложение» (одно и то же на всю игру). */
    fun puzzle(card: CardEntity): SentencePuzzle? = puzzles.getOrPut(card.id) {
        SentencePuzzle.fromExamples(listOf(card.example1, card.example2).shuffled(), chunk(card))
    }

    // Идиома и коллокация остаются одной плашкой: задача — встроить выражение в предложение.
    private fun chunk(card: CardEntity) = card.term.takeIf { card.deck == Deck.IDIOMS.id || card.deck == Deck.COLLOCATIONS.id }

    private var upcoming: kotlinx.coroutines.Job? = null
    private var upcomingDeck: Deck? = null
    @Volatile private var stopUpcoming = false

    /**
     * Перевод заранее, пока открыт экран настройки: оба примера начатых карточек выбранной колоды —
     * их тренировка берёт первыми. Модель грузится только здесь, не при запуске приложения.
     */
    fun prefetchUpcoming(limit: Int = 20) {
        if (game != Game.SENTENCE) return
        if (upcoming?.isActive == true) { if (upcomingDeck == deck) return else upcoming?.cancel() }
        if (c.translator.model.value.phase != com.marat.albion.data.translation.ModelState.Ready) return
        val dao = c.database.sentenceTranslations()
        val forDeck = deck
        upcomingDeck = forDeck
        stopUpcoming = false
        upcoming = viewModelScope.launch {
            val texts = dao.startedWithExamples(forDeck.id, limit).flatMap { card ->
                listOf(card.example1, card.example2).mapNotNull { SentencePuzzle.fromExamples(listOf(it), chunk(card))?.original }
            }.distinct()
            val known = dao.find(texts).map { it.text }.toSet()
            for (text in texts - known) {
                if (stopUpcoming) return@launch
                try { dao.insertIfAbsent(text, c.translator.translate(text)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            }
        }
    }

    val translatorModel = c.translator.model

    /** Переводы предложений «Соберите предложение» — готовятся для всей тренировки заранее, по порядку заданий. */
    val sentenceTranslations = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var translating: kotlinx.coroutines.Job? = null

    /**
     * Переводы оставшихся предложений: сначала из базы (переведённые раньше и поправленные вручную),
     * недостающие — моделью на устройстве, с сохранением. Повторный вызов ничего не делает.
     */
    fun prefetchTranslations(canTranslate: Boolean) {
        if (game != Game.SENTENCE || translating?.isActive == true) return
        val dao = c.database.sentenceTranslations()
        translating = viewModelScope.launch {
            val rest = items.drop(index).mapNotNull { card -> puzzle(card)?.original?.let { card.id to it } }
            val saved = dao.find(rest.map { it.second }.distinct()).associate { it.text to it.translation }
            rest.forEach { (id, text) -> saved[text]?.let { sentenceTranslations[id] = it } }
            if (!canTranslate) return@launch
            upcoming?.join()  // дождаться предложения, которое фоновый перевод уже доделывает
            for ((id, text) in rest) {
                if (id in sentenceTranslations) continue
                // Фоновый перевод мог только что сохранить это предложение.
                dao.find(listOf(text)).firstOrNull()?.let { sentenceTranslations[id] = it.translation; continue }
                try {
                    var done = ""
                    c.translator.translations(text).collect { done = it; sentenceTranslations[id] = it }
                    if (done.isNotBlank()) dao.insertIfAbsent(text, done)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
            }
        }
    }

    /** Ручная правка перевода: сохраняется навсегда и больше не перезаписывается моделью. */
    fun editTranslation(card: CardEntity, translation: String) {
        val text = puzzle(card)?.original ?: return
        val clean = translation.trim()
        if (clean.isEmpty()) return
        sentenceTranslations[card.id] = clean
        c.appScope.launch { c.database.sentenceTranslations().upsert(com.marat.albion.data.db.SentenceTranslationEntity(text, clean, edited = true)) }
    }

    private val clozes = HashMap<String, com.marat.albion.data.games.ClozeTask?>()

    /** Пример с пропуском для «Слова в контексте» — один на всю игру. */
    fun cloze(card: CardEntity): com.marat.albion.data.games.ClozeTask? = clozes.getOrPut(card.id) {
        com.marat.albion.data.games.Cloze.make(card, preferSecond = kotlin.random.Random.nextBoolean())
    }

    private val tenses = HashMap<String, TenseTask?>()

    /** Задание тренажёра времён по глаголу — случайная форма и пример, одно на всю игру. */
    fun tense(card: CardEntity): TenseTask? = tenses.getOrPut(card.id) {
        irregular(card)?.let { TenseDrill.tasks(it).randomOrNull() }
    }

    private val tenseOptions = HashMap<String, List<String>>()

    fun tenseOptions(card: CardEntity, task: TenseTask): List<String> = tenseOptions.getOrPut(card.id) { task.options.shuffled() }

    private val shuffledTokens = HashMap<String, List<Int>>()

    /** Перемешанный порядок плашек; не совпадает с правильным, если это возможно. */
    fun shuffledOrder(card: CardEntity, size: Int): List<Int> = shuffledTokens.getOrPut(card.id) {
        val identity = (0 until size).toList()
        var order = identity.shuffled()
        repeat(5) { if (order == identity && size > 1) order = identity.shuffled() }
        order
    }
}

@Composable
fun GameHost(game: Game, navigator: Navigator, initialDeck: Deck? = null, onlyMistakes: Boolean = false, initialCount: Int? = null) {
    if (game.isGrammar) {
        GrammarGameHost(game, navigator)
        return
    }
    if (game == Game.FIX_ERROR) {
        FixErrorHost(navigator)
        return
    }
    val c = LocalAppContainer.current
    val vm = viewModel(key = "game-${game.name}-${initialDeck?.id}-$onlyMistakes-$initialCount") {
        GameViewModel(c, game, initialDeck, onlyMistakes, initialCount)
    }
    when (game) {
        Game.TYPE_ANSWER -> GameFrame(vm, navigator) { card -> TypeAnswerRound(vm, card) }
        Game.IRREGULAR -> GameFrame(vm, navigator, counts = listOf(10, 20, Int.MAX_VALUE)) { card -> IrregularRound(vm, card) }
        Game.SHADOWING -> GameFrame(vm, navigator, scoreLabel = "похоже") { card -> ShadowingRound(vm, card) }
        Game.PRONUNCIATION -> GameFrame(vm, navigator, scoreLabel = "узнано") { card -> PronunciationRound(vm, card) }
        Game.SENTENCE -> GameFrame(vm, navigator, scoreLabel = "без ошибок") { card -> SentenceRound(vm, card) }
        Game.TENSES -> GameFrame(vm, navigator) { card -> TenseRound(vm, card) }
        Game.DICTATION -> GameFrame(vm, navigator) { card -> DictationRound(vm, card) }
        Game.CLOZE -> GameFrame(vm, navigator) { card -> ClozeRound(vm, card) }
        Game.MINIMAL_PAIRS -> GameFrame(vm, navigator, scoreLabel = "на слух") { card -> MinimalPairRound(vm, card) }
        Game.GRAMMAR_CHOICE, Game.GRAMMAR_GAP, Game.FIX_ERROR -> Unit
    }
}

/** Каркас игры: фазы и анимированная смена вопросов (новый въезжает справа). */
@Composable
private fun GameFrame(
    vm: GameViewModel,
    navigator: Navigator,
    counts: List<Int> = listOf(10, 20, 30),
    scoreLabel: String = "верно",
    allowRetry: Boolean = true,
    round: @Composable (CardEntity) -> Unit,
) {
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    BackHandler(enabled = vm.phase == Phase.Summary) { vm.backToSetup() }
    val available by remember(vm.deck) { vm.observeAvailable(vm.deck) }.collectAsStateWithLifecycle(0)
    val mistakeCount by remember(vm.deck) { vm.observeMistakes(vm.deck) }.collectAsStateWithLifecycle(-1)
    // Ошибок не осталось — режим «только ошибки» выключаем сам.
    LaunchedEffect(mistakeCount) { if (mistakeCount == 0) vm.onlyMistakes = false }
    // Пока выбирают колоду и длину, переводим вероятные предложения заранее.
    val translatorModel by vm.translatorModel.collectAsStateWithLifecycle()
    LaunchedEffect(vm.phase, vm.deck, translatorModel.phase) { if (vm.phase == Phase.Setup) vm.prefetchUpcoming() }

    AnimatedContent(
        targetState = vm.phase,
        transitionSpec = { practiceTransition(reduced) },
        label = "phase",
    ) { phase ->
        when (phase) {
            Phase.Setup -> GameSetup(
                game = vm.game,
                deck = vm.deck,
                count = vm.count,
                onDeck = { vm.deck = it; vm.saveOptions() },
                onCount = { vm.count = it; vm.saveOptions() },
                onStart = { vm.start() },
                onBack = navigator::back,
                counts = counts,
                mode = vm.mode,
                onMode = { vm.mode = it; vm.saveOptions() },
                mistakes = mistakeCount,
                available = if (vm.onlyMistakes) mistakeCount.coerceAtLeast(0) else available,
                onlyMistakes = vm.onlyMistakes,
                onOnlyMistakes = { vm.onlyMistakes = it },
            )
            Phase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                com.marat.albion.ui.components.LoadingState("Готовим упражнение")
            }
            Phase.Summary -> GameSummary(
                game = vm.game,
                correct = vm.correct,
                total = vm.items.size,
                mistakes = vm.mistakes.toList(),
                onRestart = { vm.start() },
                onRetryMistakes = if (allowRetry) ({ vm.retryMistakes() }) else null,
                onDone = navigator::back,
                scoreLabel = scoreLabel,
                note = vm.graded.takeIf { it > 0 }?.let { n ->
                    "$n ${com.marat.albion.ui.library.pluralRu(n, "ответ засчитан", "ответа засчитаны", "ответов засчитаны")} в повторения — эти слова не придут в «Сегодня» раньше срока."
                },
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
                    label = "round",
                ) { i ->
                    vm.items.getOrNull(i)?.let { round(it) }
                }
            }
        }
    }
}
