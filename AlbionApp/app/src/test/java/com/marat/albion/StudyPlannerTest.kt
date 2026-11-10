package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.db.CollectionWordEntity
import com.marat.albion.data.db.DayAnswer
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.repo.StudyPlanner
import com.marat.albion.data.repo.StudySource
import com.marat.albion.data.repo.Vocabulary
import com.marat.albion.data.repo.WordCards
import com.marat.albion.data.settings.NewCardOrder
import com.marat.albion.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlannerTest {
    private val now = 1_000_000_000L
    private val settings = Settings(newPerDay = 2, reviewsPerDay = 100, newOrder = NewCardOrder.FILE)

    private fun card(id: String, term: String, deck: Deck = Deck.WORDS, position: Int = 0) = CardEntity(
        id, deck.id, position, term, "", "", null, "", "перевод", "", "", null, null)

    private fun review(card: CardEntity) = ReviewStateEntity(card.id, card.deck, CardState.REVIEW, 0, 10.0, 5.0,
        due = now - 1, lastReview = now - 10, reps = 3, lapses = 0)

    private fun member(collection: Long, card: CardEntity) =
        CollectionWordEntity(collection, card.deck, WordCards.key(card.term), now)

    private val cat = card("cat", "cat")
    private val dog = card("dog", "dog", position = 1)
    private val fox = card("fox", "fox", position = 2)
    private val giveUp = card("give", "give up", Deck.PHRASAL)
    private val run = card("run", "run")

    private fun vocabulary(collections: List<CollectionEntity> = emptyList(), members: List<CollectionWordEntity> = emptyList()) =
        Vocabulary(listOf(cat, dog, fox, giveUp, run), mapOf(run.id to review(run)), collections, members)

    @Test fun dictionaryTrainingOffersNewWordsWithinTheDailyLimit() {
        val queue = StudyPlanner.plan(StudySource.Dictionary(Deck.WORDS), vocabulary(), emptyList(), settings, now)
        assertEquals(listOf("cat", "dog"), queue.news.map { it.id })
        assertEquals(listOf("run"), queue.reviews.map { it.id })
    }

    @Test fun todayReviewsEverythingButTakesNewWordsOnlyFromCollections() {
        val none = StudyPlanner.plan(StudySource.Today, vocabulary(), emptyList(), settings, now)
        assertEquals(listOf("run"), none.reviews.map { it.id })
        assertTrue(none.news.isEmpty())

        val travel = CollectionEntity(1, "Travel", now)
        val words = vocabulary(listOf(travel), listOf(member(1, fox), member(1, giveUp)))
        val today = StudyPlanner.plan(StudySource.Today, words, emptyList(), settings, now)
        assertEquals(setOf("fox", "give"), today.news.map { it.id }.toSet())
    }

    @Test fun collectionMixesDictionariesAndKeepsItsOwnNewLimit() {
        val travel = CollectionEntity(1, "Travel", now)
        val words = vocabulary(listOf(travel), listOf(member(1, cat), member(1, dog), member(1, giveUp), member(1, run)))
        val fresh = StudyPlanner.plan(StudySource.Collection(1), words, emptyList(), settings, now)
        assertEquals(2, fresh.news.size)
        assertEquals(listOf("run"), fresh.reviews.map { it.id })

        // Новое слово коллекции, выученное через словарь, тоже расходует её лимит.
        val answered = listOf(DayAnswer("cat", Deck.WORDS.id, CardState.NEW))
        assertEquals(1, StudyPlanner.plan(StudySource.Collection(1), words, answered, settings, now).news.size)
        // Ответ на слово вне коллекции её лимит не трогает.
        val elsewhere = listOf(DayAnswer("fox", Deck.WORDS.id, CardState.NEW))
        assertEquals(2, StudyPlanner.plan(StudySource.Collection(1), words, elsewhere, settings, now).news.size)
    }

    @Test fun wordInSeveralCollectionsAppearsOnceInToday() {
        val a = CollectionEntity(1, "A", now)
        val b = CollectionEntity(2, "B", now + 1)
        val words = vocabulary(listOf(a, b), listOf(member(1, cat), member(2, cat), member(2, dog)))
        val today = StudyPlanner.plan(StudySource.Today, words, emptyList(), settings, now)
        assertEquals(listOf("cat", "dog"), today.news.map { it.id })
    }

    @Test fun overviewCountsCollectionsAndToday() {
        val travel = CollectionEntity(1, "Travel", now)
        val words = vocabulary(listOf(travel), listOf(member(1, cat), member(1, run), member(1, card("gone", "missing"))))
        val overview = StudyPlanner.overview(words, emptyList(), settings, now)
        val summary = overview.collections.single()
        assertEquals("Слово, которого больше нет в словаре, не считается", 2, summary.total)
        assertEquals(1, summary.started)
        assertEquals(1, summary.due)
        assertEquals(1, summary.newAvailable)
        assertEquals(1, overview.today.due)
        assertEquals(1, overview.today.newAvailable)
        assertEquals(2, overview.dictionaries.first { it.deck == Deck.WORDS }.newAvailable)
    }
}
