package com.marat.albion.data.repo

import androidx.room.withTransaction
import com.marat.albion.data.content.Deck
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.DayCount
import com.marat.albion.data.db.DeckCounts
import com.marat.albion.data.db.GameCardStatEntity
import com.marat.albion.data.db.PracticeNeed
import com.marat.albion.data.db.GameAnswerLogEntity
import com.marat.albion.data.db.GameTotals
import com.marat.albion.data.db.ReviewLogEntity
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.Memory
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.settings.Settings
import com.marat.albion.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

class StudyRepository(
    private val db: AlbionDatabase,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    private val cards = db.cards()
    private val reviews = db.reviews()
    private val games = db.gameActivity()
    private val collections = db.collections()

    // ---- время учебного дня ----

    fun dayStart(now: Long, hour: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (c.get(Calendar.HOUR_OF_DAY) < hour) c.add(Calendar.DAY_OF_YEAR, -1)
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Сдвиг для группировки по дням в SQL: day = (t + shift) / сутки. */
    fun dayShift(hour: Int, now: Long = System.currentTimeMillis()): Long =
        TimeZone.getDefault().getOffset(now).toLong() - hour * 3_600_000L

    fun studyDay(t: Long, hour: Int) = (t + dayShift(hour, t)) / Fsrs.DAY

    /** Тик раз в минуту — чтобы счётчики «к повторению» обновлялись сами. */
    val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    // ---- главный экран ----

    private fun observeVocabulary(): Flow<Vocabulary> =
        combine(
            cards.observeAll().map(WordCards::group), reviews.observeStates(),
            collections.observeAll(), collections.observeWords(),
        ) { words, states, owned, members ->
            Vocabulary(words, states.associateBy { it.cardId }, owned, members)
        }.flowOn(Dispatchers.Default)

    private suspend fun vocabulary(deck: Deck? = null) = Vocabulary(
        WordCards.group(if (deck == null) cards.all() else cards.inDeck(deck.id)),
        reviews.states().associateBy { it.cardId },
        if (deck == null) collections.all() else emptyList(),
        if (deck == null) collections.words() else emptyList(),
    )

    /** Начало текущего учебного дня; меняется раз в сутки или при смене часа начала дня. */
    private fun observeStudyDay(): Flow<Pair<Long, Settings>> =
        combine(minuteTicker, settings.settings) { now, s -> dayStart(now, s.dayStartHour) to s }
            .distinctUntilChanged()

    /** Словари, коллекции и занятие «Сегодня» — из одной подписки на слова и память. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOverview(): Flow<StudyOverview> =
        observeStudyDay().flatMapLatest { (since, s) ->
            combine(observeVocabulary(), reviews.observeReviewsSince(since), minuteTicker) { words, _, now -> words to now }
                .map { (words, now) -> overviewOf(words, s, now) }
        }.flowOn(Dispatchers.Default)

    fun observeSummaries(): Flow<List<DeckSummary>> = observeOverview().map { it.dictionaries }

    /** Число слов в каждом словаре — без расчёта очередей. */
    fun observeWordCounts(): Flow<Map<String, Int>> =
        cards.observeAll().map { rows -> WordCards.group(rows).groupingBy { it.deck }.eachCount() }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    private suspend fun overviewOf(words: Vocabulary, s: Settings, now: Long): StudyOverview =
        StudyPlanner.overview(words, reviews.answersSince(dayStart(now, s.dayStartHour)), s, now)

    // ---- занятие ----

    suspend fun buildQueue(source: StudySource, now: Long = System.currentTimeMillis()): SessionQueue {
        val s = settings.current()
        return db.withTransaction {
            val words = vocabulary((source as? StudySource.Dictionary)?.deck)
            StudyPlanner.plan(source, words, reviews.answersSince(dayStart(now, s.dayStartHour)), s, now)
        }
    }

    suspend fun buildQueue(deck: Deck, now: Long = System.currentTimeMillis()) = buildQueue(StudySource.Dictionary(deck), now)

    private suspend fun word(id: String): CardEntity? =
        cards.wordMembers(id).takeIf { it.isNotEmpty() }?.let(WordCards::combine)

    private suspend fun wordState(card: CardEntity): ReviewStateEntity? {
        val states = WordCards.members(card).map { it.id }.chunked(500)
            .flatMap { reviews.statesFor(it) }.associateBy { it.cardId }
        return WordCards.state(card, states)
    }

    suspend fun memory(cardId: String): Memory =
        word(cardId)?.let { wordState(it)?.toMemory() } ?: Memory()

    suspend fun scheduler(): Fsrs = schedulerFor(settings.current())

    /** Планировщик с персональными параметрами, если пользователь их подобрал. */
    private fun schedulerFor(s: Settings) = Fsrs(
        desiredRetention = s.desiredRetention.toDouble(),
        w = Fsrs.decodeParams(s.fsrsParams) ?: Fsrs.DEFAULT_W,
        studyDay = { t -> studyDay(t, s.dayStartHour) },
    )

    /** Подбирает параметры FSRS по истории ответов и сохраняет их, если прогноз стал точнее. */
    suspend fun optimizeParams(onProgress: (Float) -> Unit): com.marat.albion.data.fsrs.OptimizeOutcome =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val current = settings.current()
            val events = reviews.allEvents()
            val initial = Fsrs.decodeParams(current.fsrsParams) ?: Fsrs.DEFAULT_W
            val outcome = com.marat.albion.data.fsrs.FsrsOptimizer(events, initial) { t -> studyDay(t, current.dayStartHour) }
                .optimize(onProgress = onProgress)
            if (outcome is com.marat.albion.data.fsrs.OptimizeOutcome.Done && outcome.improved) {
                val applied = settings.setFsrsParamsIfUnchanged(
                    current.fsrsParams, current.fsrsUpdatedAt,
                    Fsrs.encodeParams(outcome.params), outcome.reviews, outcome.improvementPercent.toFloat(),
                )
                if (!applied) return@withContext com.marat.albion.data.fsrs.OptimizeOutcome.Superseded
            }
            outcome
        }

    suspend fun resetParams() = settings.setFsrsParams(null, 0, 0f)

    /** Для напоминаний: сколько ответов сегодня (карточки и практика) и сколько карточек ждут повторения. */
    suspend fun todayStatus(now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val s = settings.current()
        val since = dayStart(now, s.dayStartHour)
        // «Ждут повторения» — с учётом дневных лимитов, как на главном экране.
        val due = overviewOf(vocabulary(), s, now).today.due
        return (reviews.countAllSince(since) + games.countAnswersSince(since)) to due
    }

    /** Сохраняет ответ. Возвращает id записи журнала (для отмены) и новую память. */
    suspend fun answer(card: CardEntity, rating: Rating, durationMs: Long, now: Long = System.currentTimeMillis()): Pair<Long, Memory> {
        val s = settings.current()
        val fsrs = schedulerFor(s)
        // Чтение прежнего состояния и обе записи — одна атомарная операция.
        return db.withTransaction {
            val combined = word(card.id) ?: card
            val prev = wordState(combined)
            val before = prev?.toMemory() ?: Memory()
            var next = fsrs.review(before, rating, now)
            if (next.state == CardState.REVIEW) {
                // Интервалы в днях привязываем к началу учебного дня, как в Anki.
                val days = max(1, ((next.due - now).toDouble() / Fsrs.DAY).roundToInt())
                next = next.copy(due = dayStart(now, s.dayStartHour) + days * Fsrs.DAY)
            }
            reviews.upsertState(next.toEntity(combined.id, combined.deck))
            val logId = reviews.insertLog(
                ReviewLogEntity(
                    cardId = combined.id, deck = combined.deck, rating = rating.value, reviewedAt = now,
                    stateBefore = before.state, durationMs = durationMs.coerceIn(0, MAX_DURATION),
                    prevStability = prev?.stability, prevDifficulty = prev?.difficulty, prevDue = prev?.due,
                    prevLastReview = prev?.lastReview, prevStep = prev?.step, prevReps = prev?.reps, prevLapses = prev?.lapses,
                )
            )
            logId to next
        }
    }

    /** Отменяет ответ: восстанавливает прежнее состояние карточки. */
    suspend fun undo(logId: Long): CardEntity? = db.withTransaction {
        val log = reviews.log(logId) ?: return@withTransaction null
        if (log.stateBefore == CardState.NEW || log.prevStability == null) {
            reviews.deleteState(log.cardId)
        } else {
            reviews.upsertState(
                ReviewStateEntity(
                    cardId = log.cardId, deck = log.deck, state = log.stateBefore, step = log.prevStep ?: 0,
                    stability = log.prevStability, difficulty = log.prevDifficulty ?: 5.0,
                    due = log.prevDue ?: log.reviewedAt, lastReview = log.prevLastReview ?: 0,
                    reps = log.prevReps ?: 0, lapses = log.prevLapses ?: 0,
                )
            )
        }
        reviews.deleteLog(logId)
        word(log.cardId)
    }

    // ---- просмотр ----

    fun browse(deck: Deck, query: String, filter: Int): Flow<List<CardEntity>> {
        val term = query.trim()
        return observeVocabulary().map { words ->
            words.cards.filter { card ->
                val state = WordCards.state(card, words.states)
                card.deck == deck.id && (card.term.contains(term, ignoreCase = true) || WordCards.members(card).any {
                    it.meaningRu.contains(term, ignoreCase = true) || it.meaningEn.contains(term, ignoreCase = true)
                }) && when (filter) {
                    1 -> state == null
                    2 -> state?.state in listOf(CardState.LEARNING, CardState.RELEARNING)
                    3 -> state?.state == CardState.REVIEW
                    4 -> state != null && state.due <= System.currentTimeMillis()
                    else -> true
                }
            }.sortedWith(compareBy<CardEntity> { if (term.isNotEmpty() && it.term.startsWith(term, true)) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.term })
        }.flowOn(Dispatchers.Default)
    }

    /** Слова коллекции по алфавиту — для её экрана. */
    fun observeCollectionWords(id: Long): Flow<List<CardEntity>> = observeVocabulary().map { words ->
        words.inCollection(id).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.term })
    }.flowOn(Dispatchers.Default)

    private fun gameState(card: CardEntity, stats: Map<String, GameCardStatEntity>): GameCardStatEntity? {
        val members = WordCards.members(card).mapNotNull { stats[it.id] }
        val latest = members.maxWithOrNull(compareBy<GameCardStatEntity> { it.lastSeen }
            .thenBy { if (it.cardId == card.id) 1 else 0 }) ?: return null
        return latest.copy(wrong = members.sumOf { it.wrong })
    }

    /** Select whole words before applying the requested count, including every translation. */
    suspend fun gamePool(
        deck: Deck, game: String, count: Int,
        withAudio: Boolean = false, onlyMistakes: Boolean = false,
    ): List<CardEntity> = db.withTransaction {
        val words = vocabulary(deck)
        val stats = cards.gameStats().filter { it.game == game }.associateBy { it.cardId }
        words.cards.shuffled().filter {
            (!withAudio || it.audio != null) && (!onlyMistakes || gameState(it, stats)?.isMistake == true)
        }.sortedBy {
            when {
                gameState(it, stats)?.isMistake == true -> 0
                WordCards.state(it, words.states) != null -> 1
                else -> 2
            }
        }.take(count)
    }

    fun observeGameMistakes(deck: Deck, game: String, withAudio: Boolean = false): Flow<Int> =
        combine(cards.observeAll(), cards.observeGameStats()) { rows, allStats ->
            val stats = allStats.filter { it.game == game }.associateBy { it.cardId }
            WordCards.group(rows.filter { it.deck == deck.id }).count {
                (!withAudio || it.audio != null) && gameState(it, stats)?.isMistake == true
            }
        }.flowOn(Dispatchers.Default)

    fun observePracticeNeeds(): Flow<List<PracticeNeed>> =
        combine(cards.observeAll(), cards.observeGameStats()) { rows, allStats ->
            val words = WordCards.group(rows)
            allStats.groupBy { it.game }.filterKeys {
                it in setOf("TYPE_ANSWER", "IRREGULAR", "PRONUNCIATION", "SHADOWING")
            }.flatMap { (game, gameStats) ->
                val stats = gameStats.associateBy { it.cardId }
                words.filter { card ->
                    gameState(card, stats)?.isMistake == true &&
                        (game !in setOf("PRONUNCIATION", "SHADOWING") || card.audio != null)
                }.groupBy { it.deck }.map { (deck, mistakes) ->
                    PracticeNeed(game, deck, mistakes.size, mistakes.maxOf { gameState(it, stats)!!.lastSeen })
                }
            }
        }.flowOn(Dispatchers.Default)

    /** Запоминает ответ в игре: ошибка ставит слово в начало следующих игр, два верных подряд — снимают. */
    suspend fun recordGame(
        cardId: String, game: String, ok: Boolean, durationMs: Long = 0,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        val combined = word(cardId)
        val targetId = combined?.id ?: cardId
        val memberIds = combined?.let { WordCards.members(it).map { c -> c.id } } ?: listOf(cardId)
        val prior = memberIds.mapNotNull { cards.gameStat(it, game) }
        val schedule = prior.maxWithOrNull(compareBy<com.marat.albion.data.db.GameCardStatEntity> { it.lastSeen }
            .thenBy { if (it.cardId == targetId) 1 else 0 })
        val old = cards.gameStat(targetId, game)
        cards.upsertGameStat(
            com.marat.albion.data.db.GameCardStatEntity(
                cardId = targetId,
                game = game,
                correct = (old?.correct ?: 0) + if (ok) 1 else 0,
                wrong = (old?.wrong ?: 0) + if (ok) 0 else 1,
                streak = if (ok) (schedule?.streak ?: 0) + 1 else 0,
                lastSeen = now,
            )
        )
        games.insert(GameAnswerLogEntity(game = game, itemId = targetId, correct = ok, answeredAt = now,
            durationMs = durationMs.coerceIn(0, MAX_GAME_DURATION)))
    }

    /** Грамматические задания для игры; level = "" — все уровни. */
    suspend fun grammarPool(kind: String, level: String, count: Int) =
        db.grammarTasks().pool(kind, level, count)

    fun observeGrammarTaskCount(kind: String) = db.grammarTasks().observeCount(kind)

    /** «Проверь себя» после темы учебника. */
    suspend fun topicPool(topic: Int) = com.marat.albion.data.grammar.GrammarQuizSelection.select(db.grammarTasks().topicPool(topic))

    fun observeTopicTaskCount(topic: Int) = db.grammarTasks().observeTopicCount(topic)

    suspend fun recordGrammar(
        taskId: String, ok: Boolean, kind: String, durationMs: Long = 0,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        val dao = db.grammarTasks()
        val old = dao.stat(taskId)
        dao.upsertStat(
            com.marat.albion.data.db.GrammarTaskStatEntity(
                taskId = taskId,
                correct = (old?.correct ?: 0) + if (ok) 1 else 0,
                wrong = (old?.wrong ?: 0) + if (ok) 0 else 1,
                lastSeen = now,
            )
        )
        games.insert(GameAnswerLogEntity(game = if (kind == "input") "GRAMMAR_GAP" else "GRAMMAR_CHOICE",
            itemId = taskId, correct = ok, answeredAt = now, durationMs = durationMs.coerceIn(0, MAX_GAME_DURATION)))
    }

    fun observeCard(id: String): Flow<CardEntity?> = cards.observeWordMembers(id)
        .map { it.takeIf { rows -> rows.isNotEmpty() }?.let(WordCards::combine) }
        .flowOn(Dispatchers.Default)

    fun observeState(id: String): Flow<ReviewStateEntity?> =
        combine(observeCard(id), reviews.observeStates()) { card, states ->
            card?.let { WordCards.state(it, states.associateBy { s -> s.cardId }) }
        }.flowOn(Dispatchers.Default)

    suspend fun resetCard(id: String) = db.withTransaction {
        cards.wordMembers(id).forEach { reviews.deleteState(it.id) }
    }
    suspend fun resetDeck(deck: Deck) = reviews.resetDeck(deck.id)

    fun irregular(card: CardEntity): IrregularData? {
        if (card.deck != Deck.IRREGULAR.id) return null
        val entries = WordCards.members(card).mapNotNull { row ->
            row.extra?.let { runCatching { json.decodeFromString(IrregularData.serializer(), it) }.getOrNull() }
        }
        val first = entries.firstOrNull() ?: return null
        if (entries.size == 1) return first
        return IrregularData(first.forms.map { form ->
            val matching = entries.flatMap { it.forms }.filter { it.label == form.label }
            form.copy(
                variants = matching.flatMap { it.variants }.groupBy { it.text to it.ipa }.values.map { variants ->
                    variants.first().copy(audio = variants.flatMap { it.audio }.distinct())
                },
                examples = matching.flatMap { it.examples }.distinct(),
            )
        }, entries.map { it.note }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
    }

    // ---- статистика ----

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStats(): Flow<Stats> = combine(minuteTicker, settings.settings.map { it.dayStartHour }.distinctUntilChanged()) { now, hour ->
        dayStart(now, hour) to hour
    }.distinctUntilChanged().flatMapLatest { (todayStart, hour) ->
        val shift = dayShift(hour, todayStart)
        val today = (todayStart + shift) / Fsrs.DAY
        val monthAgo = todayStart - 29 * Fsrs.DAY
        // Одна подписка на все карточки: из неё и прогноз, и счётчики словарей.
        val vocabulary = combine(observeVocabulary(), minuteTicker) { words, now ->
            val forecast = words.cards.mapNotNull { WordCards.state(it, words.states) }
                .filter { it.due < todayStart + 15 * Fsrs.DAY }
                .groupBy { (it.due + shift) / Fsrs.DAY }
                .map { (day, states) -> DayCount(day, states.size) }.sortedBy { it.day }
            forecast to words.counts(now)
        }
        combine(
            combine(reviews.observeDaily(monthAgo, shift), games.observeDaily(monthAgo, shift)) { cards, games ->
                (cards + games).groupBy { it.day }.map { (day, counts) -> DayCount(day, counts.sumOf { it.count }) }.sortedBy { it.day }
            },
            vocabulary,
            combine(reviews.observeActiveDays(shift), games.observeActiveDays(shift)) { cards, games ->
                (cards + games).distinct().sortedDescending()
            },
            combine(reviews.observeReviewAnswers(monthAgo), reviews.observeReviewPassed(monthAgo)) { a, p -> a to p },
            combine(
                reviews.observeTotalReviews(),
                combine(reviews.observeTimeSince(todayStart), games.observeTimeSince(todayStart)) { cards, games -> cards + games },
                reviews.observeReviewsSince(todayStart),
                combine(games.observeTotals(), games.observeAnswersSince(todayStart)) { totals, todayCount -> totals to todayCount },
            ) { total, time, todayCount, games -> StatsSummary(total, time, todayCount, games.first, games.second) },
        ) { daily, (forecast, counts), activeDays, retention, misc ->
            Stats(
                today = today,
                daily = daily,
                forecast = forecast.map { if (it.day < today) DayCount(today, it.count) else it }
                    .groupBy { it.day }.map { (d, l) -> DayCount(d, l.sumOf { it.count }) },
                streak = streak(activeDays, today),
                retention = if (retention.first == 0) null else retention.second.toFloat() / retention.first,
                retentionSample = retention.first,
                totalReviews = misc.total,
                timeTodayMs = misc.time,
                reviewsToday = misc.today,
                counts = counts,
                games = misc.games,
                gameAnswersToday = misc.gamesToday,
            )
        }
    }

    private fun streak(daysDesc: List<Long>, today: Long): Int {
        if (daysDesc.isEmpty()) return 0
        var expected = if (daysDesc.first() == today) today else today - 1
        var n = 0
        for (d in daysDesc) {
            if (d == expected) { n++; expected-- } else if (d < expected) break
        }
        return n
    }

    companion object {
        /** Как в Anki: учебные шаги, наступающие в ближайшие 20 минут, показываем сразу. */
        const val LEARN_AHEAD = 20 * 60_000L
        private const val MAX_DURATION = 60_000L
        private const val MAX_GAME_DURATION = 10 * 60_000L
    }
}

private data class StatsSummary(
    val total: Int, val time: Long, val today: Int,
    val games: List<GameTotals>, val gamesToday: Int,
)

data class Stats(
    val today: Long,
    val daily: List<DayCount>,
    val forecast: List<DayCount>,
    val streak: Int,
    val retention: Float?,
    val retentionSample: Int,
    val totalReviews: Int,
    val timeTodayMs: Long,
    val reviewsToday: Int,
    val counts: List<DeckCounts>,
    val games: List<GameTotals>,
    val gameAnswersToday: Int,
) {
    val answersToday get() = reviewsToday + gameAnswersToday
    val totalGameAnswers get() = games.sumOf { it.answers }
    val gameAccuracy get() = if (totalGameAnswers == 0) null else games.sumOf { it.correct }.toFloat() / totalGameAnswers
}

fun ReviewStateEntity.toMemory() = Memory(state, step, stability, difficulty, due, lastReview, reps, lapses)

fun Memory.toEntity(cardId: String, deck: String) = ReviewStateEntity(
    cardId = cardId, deck = deck, state = state, step = step, stability = stability,
    difficulty = difficulty, due = due, lastReview = lastReview, reps = reps, lapses = lapses,
)
