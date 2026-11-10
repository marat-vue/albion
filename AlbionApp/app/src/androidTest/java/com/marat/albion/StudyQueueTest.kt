package com.marat.albion

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.repo.CollectionRepository
import com.marat.albion.data.repo.DeckSummary
import com.marat.albion.data.repo.StudySource
import com.marat.albion.data.repo.StudyRepository
import com.marat.albion.data.settings.NewCardOrder
import com.marat.albion.data.settings.Settings
import com.marat.albion.data.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Доступность на главном экране проверяется вместе с настоящей очередью и SQLite. */
@RunWith(AndroidJUnit4::class)
class StudyQueueTest {
    private lateinit var db: AlbionDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var originalSettings: Settings
    private lateinit var repo: StudyRepository
    private lateinit var collections: CollectionRepository
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AlbionDatabase::class.java).build()
        settings = SettingsRepository(context)
        originalSettings = settings.current()
        settings.setNewPerDay(20)
        settings.setReviewsPerDay(200)
        settings.setNewOrder(NewCardOrder.FILE)
        settings.setDayStartHour(4)
        repo = StudyRepository(db, settings, Json)
        collections = CollectionRepository(db)
    }

    @After
    fun tearDown() = runBlocking {
        settings.setNewPerDay(originalSettings.newPerDay)
        settings.setReviewsPerDay(originalSettings.reviewsPerDay)
        settings.setNewOrder(originalSettings.newOrder)
        settings.setDayStartHour(originalSettings.dayStartHour)
        db.close()
    }

    private fun card(id: String, term: String = id, position: Int = 0, level: String = "A1") =
        CardEntity(id, Deck.WORDS.id, position, term, "", "noun", level, "", "", "", "", null, null)

    private suspend fun scheduled(card: CardEntity, state: Int = CardState.REVIEW, due: Long = now - Fsrs.DAY) {
        db.cards().upsert(listOf(card))
        db.reviews().upsertState(ReviewStateEntity(
            card.id, card.deck, state, 0, 5.0, 5.0, due, now - 5 * Fsrs.DAY, 3, 0,
        ))
    }

    private suspend fun summary(deck: Deck = Deck.WORDS): DeckSummary =
        withTimeout(5_000) { repo.observeSummaries().first().first { it.deck == deck } }

    private suspend fun assertAvailable(due: Int, news: Int) {
        val summary = summary()
        val queue = repo.buildQueue(Deck.WORDS, now)
        assertEquals("Главный экран: повторения", due, summary.due)
        assertEquals("Главный экран: новые", news, summary.newAvailable)
        assertEquals("Очередь: повторения и учебные шаги", due, queue.reviews.size + queue.learning.size)
        assertEquals("Очередь: новые", news, queue.news.size)
        assertEquals(due + news, summary.toStudy)
    }

    @Test
    fun todayQueueReviewsEveryDictionaryButTakesNewWordsOnlyFromCollections() = runBlocking {
        // Одинаковый термин в разных словарях — не сиблинги: оба значения доступны сегодня.
        scheduled(card("w1", "run"))
        scheduled(card("v1", "run").copy(deck = Deck.IRREGULAR.id))
        val cat = card("w2", "cat", position = 1)
        db.cards().upsert(listOf(cat, card("p1", "give up").copy(deck = Deck.PHRASAL.id)))

        val today = repo.buildQueue(StudySource.Today, now)
        val perDeck = Deck.entries.map { repo.buildQueue(it, now) }
        assertEquals(perDeck.sumOf { it.reviews.size }, today.reviews.size)
        assertEquals(setOf("w1", "v1"), today.reviews.map { it.id }.toSet())
        assertTrue("Словари не дают новых слов в «Сегодня»", today.news.isEmpty())
        assertEquals(setOf("w2"), repo.buildQueue(Deck.WORDS, now).news.map { it.id }.toSet())

        val travel = collections.create("Travel")
        collections.add(travel, listOf(cat))
        assertEquals(listOf("w2"), repo.buildQueue(StudySource.Today, now).news.map { it.id })
        assertEquals(listOf("w2"), repo.buildQueue(StudySource.Collection(travel), now).news.map { it.id })
    }

    @Test
    fun answeringAWordSchedulesAllMeaningsTogetherAndUndoRestoresTheSharedSchedule() = runBlocking {
        val first = card("first", "take off")
        val second = card("second", " TAKE OFF ")
        scheduled(first)
        scheduled(second)
        assertAvailable(due = 1, news = 0)

        val (logId, _) = repo.answer(first, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 0)
        assertEquals(0, repo.todayStatus(now).second)
        assertNotNull(db.reviews().state(second.id))
        assertTrue(repo.buildQueue(Deck.WORDS, now + Fsrs.DAY).reviews.isEmpty())
        val due = repo.memory(first.id).due
        assertEquals(listOf(first.id), repo.buildQueue(Deck.WORDS, due).reviews.map { it.id })

        repo.undo(logId)
        assertAvailable(due = 1, news = 0)
        assertEquals(1, repo.todayStatus(now).second)
    }

    @Test
    fun unstartedSiblingsDoNotKeepTheStartButtonAvailable() = runBlocking {
        val first = card("first", "about", position = 0)
        val second = card("second", "about", position = 1)
        val other = card("other", "book", position = 2)
        db.cards().upsert(listOf(first, second, other))
        assertAvailable(due = 0, news = 2)
        repo.answer(first, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 1)
        repo.answer(other, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 0)
        assertNull(db.reviews().state(second.id))
        assertEquals(2, summary().total)
        assertEquals(0, summary().total - summary().started)
    }

    @Test
    fun learningStepsRemainAvailableEvenWhenTheirTermWasAnsweredAndDailyLimitsAreReached() = runBlocking {
        val learning = card("learning", "take off")
        db.cards().upsert(listOf(learning, card("new-sibling", "take off", position = 1)))
        repo.answer(learning, Rating.Good, 100, now)
        scheduled(card("due-sibling", "take off", position = 2))
        scheduled(card("soon"), CardState.LEARNING, now + 19 * Fsrs.MINUTE)
        scheduled(card("later"), CardState.RELEARNING, now + 21 * Fsrs.MINUTE)
        settings.setNewPerDay(0)
        settings.setReviewsPerDay(0)

        assertAvailable(due = 2, news = 0)
        assertEquals(setOf("learning", "soon"), repo.buildQueue(Deck.WORDS, now).learning.map { it.id }.toSet())
        assertEquals(3, summary().learning)
        assertEquals(2, repo.todayStatus(now).second)
    }

    @Test
    fun dailyLimitsCountAnswersAndAreSharedWithTheQueue() = runBlocking {
        settings.setNewPerDay(2)
        settings.setReviewsPerDay(1)
        val review = card("review")
        scheduled(review)
        scheduled(card("other-review"))
        val first = card("first-new", position = 0)
        val second = card("second-new", position = 1)
        db.cards().upsert(listOf(first, second, card("third-new", position = 2)))
        assertAvailable(due = 1, news = 2)

        repo.answer(review, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 2)
        repo.answer(first, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 1)
        repo.answer(second, Rating.Easy, 100, now)
        assertAvailable(due = 0, news = 0)
    }

    @Test
    fun availableCardsBeyondManyBuriedSiblingsAreStillFound() = runBlocking {
        settings.setNewPerDay(2)
        settings.setReviewsPerDay(1)
        val answered = card("answered", "buried")
        db.cards().upsert(listOf(answered))
        repo.answer(answered, Rating.Easy, 100, now)
        db.withTransaction {
            db.cards().upsert((0 until 90).map { card("new-$it", "buried", position = it) })
            repeat(90) { scheduled(card("review-$it", "buried"), due = now - 2 * Fsrs.DAY) }
            scheduled(card("available-review"))
            db.cards().upsert(listOf(card("available-new", position = 100)))
        }

        assertAvailable(due = 1, news = 1)
        val queue = repo.buildQueue(Deck.WORDS, now)
        assertEquals(listOf("available-review"), queue.reviews.map { it.id })
        assertEquals(listOf("available-new"), queue.news.map { it.id })
    }

    @Test
    fun newSiblingsOfPendingReviewsDoNotFillNewSlots() = runBlocking {
        settings.setReviewsPerDay(1)
        scheduled(card("review-a", "a"))
        scheduled(card("review-b", "b"))
        db.cards().upsert(listOf(card("new-a", "a"), card("new-b", "b"), card("fresh")))
        repeat(5) {
            assertAvailable(due = 1, news = 1)
            assertEquals(listOf("fresh"), repo.buildQueue(Deck.WORDS, now).news.map { it.id })
        }
    }

    @Test
    fun answeringATermInAnotherDeckDoesNotBuryThisDeck() = runBlocking {
        val first = card("first", "take off")
        val otherDeck = card("other-deck", "take off").copy(deck = Deck.PHRASAL.id)
        scheduled(first)
        scheduled(otherDeck)
        repo.answer(first, Rating.Easy, 100, now)

        assertAvailable(due = 0, news = 0)
        assertEquals(1, summary(Deck.PHRASAL).due)
        assertEquals(listOf(otherDeck.id), repo.buildQueue(Deck.PHRASAL, now).reviews.map { it.id })
        assertEquals(1, repo.todayStatus(now).second)
    }

    @Test
    fun newCardOrderIsPreservedAndRandomOrderKeepsCountsConsistent() = runBlocking {
        settings.setNewPerDay(1)
        db.cards().upsert(listOf(card("advanced", position = 0, level = "C1"), card("basic", position = 1)))
        settings.setNewOrder(NewCardOrder.LEVEL)
        assertEquals(listOf("basic"), repo.buildQueue(Deck.WORDS, now).news.map { it.id })
        settings.setNewOrder(NewCardOrder.FILE)
        assertEquals(listOf("advanced"), repo.buildQueue(Deck.WORDS, now).news.map { it.id })
        settings.setNewOrder(NewCardOrder.RANDOM)
        repeat(5) { assertAvailable(due = 0, news = 1) }
    }

    @Test
    fun summariesRefreshAfterAnAnswerWithoutWaitingForTheNextMinute() = runBlocking {
        val first = card("first", "take off")
        scheduled(first)
        scheduled(card("second", "take off"))
        withTimeout(5_000) {
            val initial = CompletableDeferred<Unit>()
            val updated = async {
                repo.observeSummaries()
                    .onEach { if (it.first { it.deck == Deck.WORDS }.due == 1) initial.complete(Unit) }
                    .first { it.first { it.deck == Deck.WORDS }.due == 0 }
            }
            initial.await()
            repo.answer(first, Rating.Easy, 100, now)
            assertEquals(0, updated.await().first { it.deck == Deck.WORDS }.toStudy)
        }
    }
}
