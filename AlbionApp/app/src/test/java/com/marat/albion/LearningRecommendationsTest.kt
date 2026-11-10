package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.GrammarProgressEntity
import com.marat.albion.data.db.PracticeNeed
import com.marat.albion.data.grammar.*
import com.marat.albion.data.repo.TodaySummary
import com.marat.albion.ui.games.Game
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.stats.StudyAdvicePolicy
import org.junit.Assert.*
import org.junit.Test

class LearningRecommendationsTest {
    private val now = 100_000_000L
    private val first = Topic("one", 1, "First lesson", "A1", TopicKind.Lesson)
    private val second = Topic("two", 2, "Second lesson", "A2", TopicKind.Lesson, prerequisites = listOf(1))
    private val book = GrammarBook(listOf(first, second), emptyList(), emptyList(), null)
    private fun progress(id: String = first.id) = GrammarProgressEntity(id, now - 100, false, now - 200)
    private fun step(progress: Map<String, GrammarProgressEntity> = emptyMap(), quizzes: Set<Int> = setOf(1, 2), last: String? = null) =
        GrammarNextStep.recommend(book, progress, quizzes, now, lastId = last)
    private fun need(game: Game = Game.TYPE_ANSWER, deck: Deck = Deck.WORDS, count: Int = 3, seen: Long = now) =
        PracticeNeed(game.name, deck.id, count, seen)

    @Test fun unopenedLessonStartsWithReadingAndReadLessonOffersAQuiz() {
        assertEquals(GrammarNextStep.Kind.Read, step()!!.kind)
        val read = progress()
        assertEquals(GrammarNextStep.Kind.Check, step(mapOf(first.id to read))!!.kind)
        assertNull(read.masteredAt)
    }

    @Test fun dueReviewWinsOverTheLastOpenedUnfinishedLesson() {
        val p = progress().copy(masteredAt = now - 100, quizAt = now - 100, reviewAt = now - 1, reviewStage = 1)
        val next = step(mapOf(first.id to p), last = second.id)!!
        assertEquals(first, next.topic)
        assertEquals(GrammarNextStep.Kind.Review, next.kind)
    }

    @Test fun futureReviewDoesNotInterruptNewMaterial() {
        val p = progress().copy(masteredAt = now - 100, quizAt = now - 100, reviewAt = now + 1, reviewStage = 1)
        assertEquals(second, step(mapOf(first.id to p))!!.topic)
        assertEquals(GrammarNextStep.Kind.Read, step(mapOf(first.id to p))!!.kind)
    }

    @Test fun readLessonWithoutAQuizDoesNotBlockThePathOrBecomeMastered() {
        val p = progress()
        assertEquals(second, step(mapOf(first.id to p), quizzes = setOf(2))!!.topic)
        assertNull(p.masteredAt)
    }

    @Test fun prerequisitesRemainRequiredEvenWhenAnotherLevelIsSelected() {
        val next = GrammarNextStep.recommend(book, emptyMap(), setOf(1, 2), now, level = "A2", lastId = second.id)!!
        assertEquals(first, next.topic)
        assertEquals(GrammarNextStep.Kind.Read, next.kind)
    }

    @Test fun emptyAndCompletedCoursesDoNotInventALesson() {
        assertNull(GrammarNextStep.recommend(GrammarBook.EMPTY, emptyMap(), emptySet(), now))
        val completed = book.lessons.associate { it.id to progress(it.id).copy(masteredAt = now) }
        assertNull(step(completed))
    }

    @Test fun scheduledCardsTakePriorityAndUseABoundedSession() {
        val advice = StudyAdvicePolicy.choose(TodaySummary(due = 3, newAvailable = 20),
            GrammarNextStep.Step(first, GrammarNextStep.Kind.Review, "due"), listOf(need()))!!
        assertEquals(StudyRoute(answerLimit = 10), advice.destination)
    }

    @Test fun dueGrammarLeadsDirectlyToTheCorrectTopicQuiz() {
        val advice = StudyAdvicePolicy.choose(TodaySummary(), GrammarNextStep.Step(second, GrammarNextStep.Kind.Review, "due"), listOf(need()))!!
        assertEquals(TopicQuizRoute(2, second.id, second.title), advice.destination)
    }

    @Test fun mistakeAdviceCarriesDeckAndFilterInsteadOfLaunchingAGenericExercise() {
        val advice = StudyAdvicePolicy.choose(TodaySummary(), step(), listOf(need(deck = Deck.PHRASAL)))!!
        assertEquals(GameRoute(Game.TYPE_ANSWER, Deck.PHRASAL, onlyMistakes = true, count = 10), advice.destination)
    }

    @Test fun invalidAndUnsupportedMistakesCannotLeadToAnEmptyOrWrongExercise() {
        val needs = listOf(PracticeNeed("REMOVED_GAME", Deck.WORDS.id, 200, now), need(deck = Deck.BOOK_WORDS),
            need(game = Game.IRREGULAR, deck = Deck.WORDS), need(game = Game.SENTENCE), need(count = 0))
        val advice = StudyAdvicePolicy.choose(TodaySummary(), step(), needs)!!
        assertEquals(TopicRoute(first.id), advice.destination)
    }

    @Test fun strongestCurrentNeedWinsAndRecentlyUsedNeedBreaksTies() {
        val advice = StudyAdvicePolicy.choose(TodaySummary(), null, listOf(need(count = 4, seen = now - 1),
            need(game = Game.IRREGULAR, deck = Deck.IRREGULAR, count = 4), need(deck = Deck.PHRASAL, count = 1)))!!
        assertEquals(GameRoute(Game.IRREGULAR, Deck.IRREGULAR, true, 10), advice.destination)
    }

    @Test fun readGrammarIsCheckedBeforeNewVocabularyAndSmallQueuesStaySmall() {
        val decks = TodaySummary(newAvailable = 3)
        val check = step(mapOf(first.id to progress()))!!
        assertEquals(TopicQuizRoute(1, first.id, first.title), StudyAdvicePolicy.choose(decks, check, emptyList())!!.destination)
        assertEquals(StudyRoute(answerLimit = 3), StudyAdvicePolicy.choose(decks, null, emptyList())!!.destination)
        assertNull(StudyAdvicePolicy.choose(TodaySummary(), null, emptyList()))
    }
}
