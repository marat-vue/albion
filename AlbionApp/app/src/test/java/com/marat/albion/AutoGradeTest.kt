package com.marat.albion

import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.repo.AutoGrade
import com.marat.albion.data.repo.ReviewSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGradeTest {
    private val now = 1_000_000_000L

    private fun state(state: Int, due: Long) = ReviewStateEntity("w:cat#1", "w", state, 0, 12.0, 5.0, due, now - 1, 3, 0)

    @Test fun `clean answer is Good, typo or hint is Hard, mistake is Again`() {
        assertEquals(Rating.Good, AutoGrade.rating(Verdict.Correct, hinted = false))
        assertEquals(Rating.Hard, AutoGrade.rating(Verdict.Typo, hinted = false))
        assertEquals(Rating.Hard, AutoGrade.rating(Verdict.Correct, hinted = true))
        assertEquals(Rating.Again, AutoGrade.rating(Verdict.Wrong, hinted = false))
        assertEquals(Rating.Again, AutoGrade.rating(Verdict.Wrong, hinted = true))
    }

    @Test fun `only due reviews are graded`() {
        assertTrue(AutoGrade.eligible(state(CardState.REVIEW, now), now))
        assertTrue(AutoGrade.eligible(state(CardState.REVIEW, now - 5 * 86_400_000L), now))
        assertFalse("досрочно", AutoGrade.eligible(state(CardState.REVIEW, now + 1), now))
        assertFalse("новое слово", AutoGrade.eligible(null, now))
        assertFalse("учебный шаг", AutoGrade.eligible(state(CardState.LEARNING, now - 1), now))
        assertFalse("переучивание", AutoGrade.eligible(state(CardState.RELEARNING, now - 1), now))
    }

    @Test fun `practice sources are tagged by game`() {
        assertEquals("game:TYPE_ANSWER", ReviewSource.game("TYPE_ANSWER"))
    }
}
