package com.marat.albion

import com.marat.albion.data.grammar.GrammarQuizResult
import com.marat.albion.data.grammar.GrammarQuizSession
import org.junit.Assert.*
import org.junit.Test

class GrammarQuizTest {
    @Test
    fun masteryRequiresAtLeastSeventyPercentInAFullAttempt() {
        assertTrue(GrammarQuizResult(7, 10, true).passed)
        assertFalse(GrammarQuizResult(6, 10, true).passed)
        assertFalse(GrammarQuizResult(2, 3, true).passed)
        assertTrue(GrammarQuizResult(3, 4, true).passed)
        assertFalse(GrammarQuizResult(0, 0, true).updatesMastery)
    }

    @Test
    fun retryingMistakesCannotAwardOrReplaceMastery() {
        val retry = GrammarQuizSession(listOf("wrong1", "wrong2"), fullAttempt = false)
        retry.record("wrong1", true)
        retry.record("wrong2", true)
        val result = retry.result()!!
        assertEquals(2, result.correct)
        assertFalse(result.updatesMastery)
        assertFalse(result.passed)
    }

    @Test
    fun incompleteAndEmptyTestsHaveNoResult() {
        val test = GrammarQuizSession(listOf("1", "2"), true)
        assertNull(test.result())
        test.record("1", true)
        assertNull(test.result())
        assertNull(GrammarQuizSession(emptyList(), true).result())
    }

    @Test
    fun duplicateAndUnknownAnswersCannotIncreaseTheScore() {
        val test = GrammarQuizSession(listOf("1", "2"), true)
        assertTrue(test.record("1", false))
        assertFalse(test.record("1", true))
        assertFalse(test.record("other", true))
        assertTrue(test.answered("1"))
        assertFalse(test.answered("2"))
        test.record("2", true)
        assertEquals(GrammarQuizResult(1, 2, true), test.result())
    }

    @Test
    fun fullTestUsesEveryQuestionEvenWhenThereAreMoreThanTen() {
        val ids = (1..15).map(Int::toString)
        val test = GrammarQuizSession(ids, true)
        ids.dropLast(1).forEach { test.record(it, true) }
        assertNull(test.result())
        test.record(ids.last(), false)
        assertEquals(GrammarQuizResult(14, 15, true), test.result())
    }

    @Test
    fun ownAnswerClaimsLeaveTheScoreWithoutCountingAsMistakes() {
        val test = GrammarQuizSession(listOf("1", "2", "3", "4"), true)
        assertTrue(test.exclude("1"))
        assertTrue(test.answered("1"))
        assertFalse(test.record("1", true))
        assertFalse(test.exclude("1"))
        assertFalse(test.exclude("other"))
        test.record("2", true)
        test.record("3", true)
        assertNull(test.result())
        test.record("4", false)
        val result = test.result()!!
        assertEquals(GrammarQuizResult(2, 3, true, excluded = 1), result)
        assertEquals(1, test.excludedCount)
        assertTrue(result.updatesMastery)
        assertFalse(result.passed)
        assertFalse(test.record("4", true))
    }

    @Test
    fun claimingMostQuestionsDoesNotChangeMastery() {
        assertTrue(GrammarQuizResult(4, 4, true, excluded = 4).passed)
        assertFalse(GrammarQuizResult(3, 3, true, excluded = 5).updatesMastery)
        val test = GrammarQuizSession(listOf("1", "2"), true)
        test.exclude("1")
        test.exclude("2")
        assertFalse(test.result()!!.updatesMastery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScoresAreRejected() {
        GrammarQuizResult(11, 10, true)
    }
}
