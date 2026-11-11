package com.marat.albion.data.repo

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.db.CollectionWordEntity
import com.marat.albion.data.db.DayAnswer
import com.marat.albion.data.db.DeckCounts
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.settings.NewCardOrder
import com.marat.albion.data.settings.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * Откуда берётся занятие. Словари — справочники: новые слова из них приходят только
 * при занятии по всему словарю. Коллекции пользователя дают новые слова и в занятие «Сегодня».
 */
@Serializable
sealed interface StudySource {
    /** Все повторения + новые слова из коллекций. */
    @Serializable @SerialName("today") data object Today : StudySource
    @Serializable @SerialName("dictionary") data class Dictionary(val deck: Deck) : StudySource
    @Serializable @SerialName("collection") data class Collection(val id: Long) : StudySource
}

/** Сводка по словарю. */
data class DeckSummary(
    val deck: Deck,
    val total: Int = 0,
    val started: Int = 0,
    val due: Int = 0,
    val learning: Int = 0,
    val mature: Int = 0,
    val newAvailable: Int = 0,
) {
    val toStudy get() = due + newAvailable
    val progress get() = if (total == 0) 0f else started.toFloat() / total
}

/** Сводка по коллекции пользователя. */
data class CollectionSummary(
    val collection: CollectionEntity,
    val total: Int = 0,
    val started: Int = 0,
    val due: Int = 0,
    val mature: Int = 0,
    val newAvailable: Int = 0,
) {
    val toStudy get() = due + newAvailable
    val progress get() = if (total == 0) 0f else started.toFloat() / total
}

/** Занятие «Сегодня»: что ждёт повторения везде и сколько новых слов дают коллекции. */
data class TodaySummary(val due: Int = 0, val newAvailable: Int = 0) {
    val toStudy get() = due + newAvailable
}

data class StudyOverview(
    val dictionaries: List<DeckSummary> = Deck.entries.map { DeckSummary(it) },
    val collections: List<CollectionSummary> = emptyList(),
    val today: TodaySummary = TodaySummary(),
)

/** Очередь занятия: сначала учебные шаги, затем повторения, затем новые. */
data class SessionQueue(
    val learning: List<CardEntity>,
    val reviews: List<CardEntity>,
    val news: List<CardEntity>,
)

/** Слова (по одному на словарь и термин), их память и состав коллекций. */
internal class Vocabulary(
    val cards: List<CardEntity>,
    val states: Map<String, ReviewStateEntity>,
    val collections: List<CollectionEntity> = emptyList(),
    collectionWords: List<CollectionWordEntity> = emptyList(),
) {
    private val membership: Map<Long, Set<Pair<String, String>>> =
        collectionWords.groupBy { it.collectionId }.mapValues { (_, rows) -> rows.mapTo(HashSet()) { it.deck to it.term } }

    fun state(card: CardEntity) = WordCards.state(card, states)

    fun inDeck(deck: Deck) = cards.filter { it.deck == deck.id }

    fun inCollection(id: Long): List<CardEntity> {
        val words = membership[id] ?: return emptyList()
        return cards.filter { (it.deck to WordCards.key(it.term)) in words }
    }

    fun counts(now: Long): List<DeckCounts> = cards.groupBy { it.deck }.map { (deck, words) ->
        val memory = words.mapNotNull(::state)
        DeckCounts(deck, words.size, memory.size,
            memory.count { it.state == CardState.REVIEW && it.due <= now },
            memory.count { it.state in listOf(CardState.LEARNING, CardState.RELEARNING) },
            memory.count { it.state == CardState.REVIEW && it.stability >= 21 })
    }
}

/**
 * Один и тот же отбор слов питает занятие, счётчики и напоминания. Лимиты новых и повторений
 * действуют на каждый словарь и каждую коллекцию отдельно и считаются по сегодняшним ответам.
 */
internal object StudyPlanner {

    fun plan(source: StudySource, words: Vocabulary, answered: List<DayAnswer>, s: Settings, now: Long): SessionQueue =
        when (source) {
            is StudySource.Dictionary -> dictionary(source.deck, words, answered, s, now)
            is StudySource.Collection -> collection(source.id, words, answered, s, now)
            StudySource.Today -> today(words, answered, s, now)
        }

    fun overview(words: Vocabulary, answered: List<DayAnswer>, s: Settings, now: Long): StudyOverview {
        val byDeck = words.counts(now).associateBy { it.deck }
        val dictionaries = Deck.entries.map { deck ->
            val c = byDeck[deck.id] ?: return@map DeckSummary(deck)
            val plan = dictionary(deck, words, answered, s, now)
            DeckSummary(deck, c.total, c.started, plan.reviews.size + plan.learning.size, c.learning, c.mature, plan.news.size)
        }
        val collections = words.collections.map { collection ->
            val pool = words.inCollection(collection.id)
            val memory = pool.mapNotNull(words::state)
            val plan = collection(collection.id, words, answered, s, now)
            CollectionSummary(collection, pool.size, memory.size, plan.reviews.size + plan.learning.size,
                memory.count { it.state == CardState.REVIEW && it.stability >= 21 }, plan.news.size)
        }
        val today = today(words, answered, s, now)
        return StudyOverview(dictionaries, collections,
            TodaySummary(today.learning.size + today.reviews.size, today.news.size))
    }

    private fun dictionary(deck: Deck, words: Vocabulary, answered: List<DayAnswer>, s: Settings, now: Long, withNew: Boolean = true) =
        queue(words.inDeck(deck), answered.filter { it.deck == deck.id }, words, s, now, withNew)

    private fun collection(id: Long, words: Vocabulary, answered: List<DayAnswer>, s: Settings, now: Long): SessionQueue {
        val pool = words.inCollection(id)
        val ids = pool.flatMapTo(HashSet()) { card -> WordCards.members(card).map { it.id } }
        return queue(pool, answered.filter { it.cardId in ids }, words, s, now, withNew = true)
    }

    /** Повторения — по всем словарям (с их лимитами), новые слова — только из коллекций, по очереди из каждой. */
    private fun today(words: Vocabulary, answered: List<DayAnswer>, s: Settings, now: Long): SessionQueue {
        val decks = Deck.entries.map { dictionary(it, words, answered, s, now, withNew = false) }
        val news = roundRobin(words.collections.map { collection(it.id, words, answered, s, now).news }).distinctBy { it.id }
        return SessionQueue(decks.flatMap { it.learning }, decks.flatMap { it.reviews }.shuffled(), news)
    }

    private fun queue(pool: List<CardEntity>, answered: List<DayAnswer>, words: Vocabulary, s: Settings, now: Long, withNew: Boolean): SessionQueue {
        val newLeft = if (withNew) max(0, s.newPerDay - answered.count { it.stateBefore == CardState.NEW }) else 0
        val reviewLeft = max(0, s.reviewsPerDay - answered.count { it.stateBefore == CardState.REVIEW })
        val learning = pool.filter {
            val m = words.state(it)
            m != null && m.state in listOf(CardState.LEARNING, CardState.RELEARNING) && m.due <= now + StudyRepository.LEARN_AHEAD
        }.sortedBy { words.state(it)!!.due }
        // Если повторений больше лимита, первыми идут слова с наименьшей вероятностью вспомнить:
        // при любых параметрах FSRS она убывает с отношением «прошло дней / стабильность».
        val due = pool.filter {
            val m = words.state(it)
            m != null && m.state == CardState.REVIEW && m.due <= now
        }.sortedByDescending { overdueness(words.state(it)!!, now) }.take(reviewLeft).shuffled()
        if (newLeft == 0) return SessionQueue(learning, due, emptyList())
        val fresh = pool.filter { words.state(it) == null }
        val ordered = when (s.newOrder) {
            NewCardOrder.RANDOM -> fresh.shuffled()
            NewCardOrder.LEVEL -> fresh.sortedWith(compareBy<CardEntity> {
                LEVELS.indexOf(it.level).takeIf { rank -> rank >= 0 } ?: LEVELS.size
            }.thenBy { it.position })
            else -> fresh.sortedBy { it.position }
        }
        return SessionQueue(learning, due, ordered.take(newLeft))
    }

    private fun overdueness(m: ReviewStateEntity, now: Long): Double =
        if (m.stability <= 0) Double.MAX_VALUE else max(0L, now - m.lastReview).toDouble() / m.stability

    private fun <T> roundRobin(lists: List<List<T>>): List<T> {
        val total = lists.sumOf { it.size }
        val result = ArrayList<T>(total)
        var i = 0
        while (result.size < total) {
            lists.forEach { list -> list.getOrNull(i)?.let(result::add) }
            i++
        }
        return result
    }

    private val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")
}
