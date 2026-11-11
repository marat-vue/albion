package com.marat.albion

import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.Memory
import com.marat.albion.data.fsrs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsTest {
    private val fsrs = Fsrs(enableFuzz = false)
    private val t0 = 1_700_000_000_000L

    @Test
    fun newCardGoesThroughLearningSteps() {
        val first = fsrs.review(Memory(), Rating.Good, t0)
        assertEquals(CardState.LEARNING, first.state)
        assertEquals(1, first.step)
        assertEquals(10 * Fsrs.MINUTE, first.due - t0)

        val second = fsrs.review(first, Rating.Good, first.due)
        assertEquals(CardState.REVIEW, second.state)
        assertTrue("интервал не меньше суток", second.due - first.due >= Fsrs.DAY)
    }

    @Test
    fun againRestartsLearning() {
        val m = fsrs.review(Memory(), Rating.Again, t0)
        assertEquals(CardState.LEARNING, m.state)
        assertEquals(0, m.step)
        assertEquals(Fsrs.MINUTE, m.due - t0)
    }

    @Test
    fun easyOnNewCardGraduatesImmediately() {
        val m = fsrs.review(Memory(), Rating.Easy, t0)
        assertEquals(CardState.REVIEW, m.state)
        assertTrue(m.due - t0 >= Fsrs.DAY)
    }

    @Test
    fun reviewIntervalsAreOrderedAndLapseCounts() {
        var m = fsrs.review(Memory(), Rating.Good, t0)
        m = fsrs.review(m, Rating.Good, m.due)
        val now = m.due
        val p = fsrs.preview(m, now)
        val hard = p.getValue(Rating.Hard).due
        val good = p.getValue(Rating.Good).due
        val easy = p.getValue(Rating.Easy).due
        assertTrue(hard <= good && good < easy)

        val lapse = p.getValue(Rating.Again)
        assertEquals(CardState.RELEARNING, lapse.state)
        assertEquals(1, lapse.lapses)
        assertTrue(lapse.stability < m.stability)
    }

    @Test
    fun higherRetentionMeansShorterIntervals() {
        val strict = Fsrs(desiredRetention = 0.95, enableFuzz = false)
        val relaxed = Fsrs(desiredRetention = 0.85, enableFuzz = false)
        assertTrue(strict.nextIntervalDays(20.0) < relaxed.nextIntervalDays(20.0))
        // При удержании 0.9 интервал ≈ стабильности (определение S в FSRS).
        assertEquals(20, Fsrs(desiredRetention = 0.9).nextIntervalDays(20.0))
    }

    @Test
    fun repeatedSuccessGrowsIntervals() {
        var m = fsrs.review(Memory(), Rating.Good, t0)
        var now = m.due
        val intervals = mutableListOf<Long>()
        repeat(6) {
            val next = fsrs.review(m, Rating.Good, now)
            intervals += next.due - now
            m = next
            now = next.due
        }
        assertTrue(intervals.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun formatsIntervals() {
        assertEquals("10 мин", Fsrs.formatInterval(10 * Fsrs.MINUTE))
        assertEquals("3 дн", Fsrs.formatInterval(3 * Fsrs.DAY))
        assertEquals("2 мес", Fsrs.formatInterval(61 * Fsrs.DAY))
    }

    @Test
    fun `вчера 20_00 → сегодня 08_00 uses long-term formula`() {
        val hour = 3_600_000L
        val dayStartHour = 4
        val studyDay = { t: Long -> Math.floorDiv(t - dayStartHour * hour, Fsrs.DAY) }
        val byDays = Fsrs(enableFuzz = false, studyDay = studyDay)
        val midnight = Math.floorDiv(t0, Fsrs.DAY) * Fsrs.DAY
        val evening = midnight + 20 * hour
        val morning = midnight + Fsrs.DAY + 8 * hour
        val m = Memory(CardState.REVIEW, stability = 1.0, difficulty = 5.0, due = morning, lastReview = evening, reps = 3)

        assertEquals(1.0, byDays.elapsedDays(m, morning), 0.0)
        val next = byDays.review(m, Rating.Good, morning)
        val (longTerm, _) = byDays.memoryStep(1.0, 5.0, isNew = false, elapsedDays = 1.0, rating = Rating.Good)
        assertEquals(longTerm, next.stability, 1e-9)
        // По часам прошло 12 ч — короткая ветка почти не растит стабильность.
        val byHours = fsrs.review(m, Rating.Good, morning)
        assertTrue(next.stability > byHours.stability * 2)
        // Учебные шаги по-прежнему считаются по часам.
        val learning = m.copy(state = CardState.RELEARNING)
        assertEquals(0.5, byDays.elapsedDays(learning, morning), 1e-9)
    }
}
