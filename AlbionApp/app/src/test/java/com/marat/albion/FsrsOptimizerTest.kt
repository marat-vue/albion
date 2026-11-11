package com.marat.albion

import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.FsrsOptimizer
import com.marat.albion.data.fsrs.Memory
import com.marat.albion.data.fsrs.OptimizeOutcome
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.fsrs.ReviewEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class FsrsOptimizerTest {

    /**
     * Синтетический ученик: его «настоящая» память описывается параметрами [truth],
     * а повторения он проходит по расписанию стандартного FSRS. Ответ «вспомнил»
     * выпадает с вероятностью, которую даёт истинная модель.
     */
    private fun simulate(truth: DoubleArray, cards: Int, seed: Int = 7): List<ReviewEvent> {
        val rnd = Random(seed)
        val scheduler = Fsrs(enableFuzz = false)
        val real = Fsrs(w = truth, enableFuzz = false)
        val events = mutableListOf<ReviewEvent>()
        repeat(cards) { c ->
            var m = Memory()
            var realS = 0.0
            var realD = 0.0
            var now = 1_700_000_000_000L + c * 60_000L
            repeat(8) { k ->
                val rating = if (k == 0) Rating.Good else {
                    val elapsed = (now - m.lastReview).toDouble() / Fsrs.DAY
                    val p = if (elapsed < 1) 0.95 else real.recallProbability(elapsed, realS)
                    if (rnd.nextDouble() < p) Rating.Good else Rating.Again
                }
                val elapsed = if (k == 0) 0.0 else (now - m.lastReview).toDouble() / Fsrs.DAY
                val (s, d) = real.memoryStep(realS, realD, isNew = k == 0, elapsedDays = elapsed, rating = rating)
                realS = s; realD = d
                events += ReviewEvent("card$c", rating.value, now)
                m = scheduler.review(m, rating, now)
                now = m.due
            }
        }
        return events
    }

    @Test
    fun notEnoughData() {
        val outcome = FsrsOptimizer(simulate(Fsrs.DEFAULT_W, cards = 3)).optimize(iterations = 5)
        assertTrue(outcome is OptimizeOutcome.NotEnoughData)
    }

    @Test
    fun learnsBetterParametersForStrongerMemory() {
        // У этого ученика память заметно крепче стандартной: стабильность растёт быстрее.
        val truth = Fsrs.DEFAULT_W.copyOf().apply { this[8] = 2.6; this[2] = 6.0 }
        val events = simulate(truth, cards = 120)
        val optimizer = FsrsOptimizer(events)
        val outcome = optimizer.optimize(iterations = 60)
        assertTrue(outcome is OptimizeOutcome.Done)
        outcome as OptimizeOutcome.Done
        println("loss ${outcome.lossBefore} → ${outcome.lossAfter}, items ${outcome.reviews}, w8=${outcome.params[8]}")
        assertTrue("ошибка прогноза уменьшилась", outcome.lossAfter < outcome.lossBefore)
        assertTrue(outcome.improved)
        assertEquals(optimizer.validationLoss(Fsrs.DEFAULT_W), outcome.lossBefore, 1e-12)
        assertEquals(optimizer.validationLoss(outcome.params), outcome.lossAfter, 1e-12)
        assertEquals(outcome.reviews, optimizer.trainingItems + outcome.validationReviews)
        assertTrue(outcome.validationReviews >= 30)
        assertTrue("параметры в границах", outcome.params.withIndex().all { (i, v) -> v in Fsrs.BOUNDS[i] })
    }

    @Test
    fun comparesAgainstCurrentPersonalizedParameters() {
        val current = Fsrs.DEFAULT_W.copyOf().apply { this[2] = 7.0; this[8] = 2.8 }
        val optimizer = FsrsOptimizer(simulate(current, cards = 120), current)
        val outcome = optimizer.optimize(iterations = 0) as OptimizeOutcome.Done
        assertEquals(optimizer.validationLoss(current), outcome.lossBefore, 1e-12)
        assertArrayEquals(current, outcome.params, 0.0)
        assertEquals(outcome.lossBefore, outcome.lossAfter, 0.0)
        assertFalse("Текущая модель не считается улучшением самой себя", outcome.improved)
        assertTrue(kotlin.math.abs(outcome.lossBefore - optimizer.validationLoss(Fsrs.DEFAULT_W)) > 1e-4)
    }

    /** 200 независимых карточек: последние 40 ответов — отложенная история. */
    private fun changingHistory(validationRating: Rating): List<ReviewEvent> = (0 until 200).flatMap { i ->
        val start = 1_700_000_000_000L
        listOf(
            ReviewEvent("card$i", Rating.Good.value, start),
            ReviewEvent("card$i", (if (i < 160) Rating.Good else validationRating).value, start + (i + 1) * Fsrs.DAY),
        )
    }

    @Test
    fun rejectsTrainingImprovementThatMakesHeldOutPredictionsWorse() {
        val optimizer = FsrsOptimizer(changingHistory(Rating.Again))
        val outcome = optimizer.optimize(iterations = 60) as OptimizeOutcome.Done
        assertEquals(160, optimizer.trainingItems)
        assertEquals(40, outcome.validationReviews)
        assertTrue("На обучении кандидат лучше", optimizer.trainingLoss(outcome.params) < optimizer.trainingLoss(Fsrs.DEFAULT_W))
        assertTrue("Но на отложенной истории хуже", outcome.lossAfter > outcome.lossBefore)
        assertFalse("Такой кандидат не должен применяться", outcome.improved)
    }

    @Test
    fun heldOutAnswersDoNotAffectTrainingOrCandidateSelection() {
        val correct = FsrsOptimizer(changingHistory(Rating.Good)).optimize(iterations = 20) as OptimizeOutcome.Done
        val wrong = FsrsOptimizer(changingHistory(Rating.Again)).optimize(iterations = 20) as OptimizeOutcome.Done
        assertArrayEquals("Ответы из проверки не участвуют в подборе", correct.params, wrong.params, 0.0)
        assertTrue(correct.improved)
        assertFalse(wrong.improved)
    }

    @Test
    fun doesNotTuneWhenAllScoredAnswersHaveTheSameTimestamp() {
        val events = (0 until 200).flatMap { i ->
            listOf(ReviewEvent("card$i", 3, Fsrs.DAY), ReviewEvent("card$i", 3, 2 * Fsrs.DAY))
        }
        val outcome = FsrsOptimizer(events).optimize(iterations = 1)
        assertTrue(outcome is OptimizeOutcome.InsufficientSplit)
    }

    @Test
    fun resetCardStartsNewHistory() {
        val events = listOf(
            ReviewEvent("card", 3, 0, CardState.NEW),
            ReviewEvent("card", 3, 5 * Fsrs.DAY, CardState.REVIEW),
            // «Сделать новой»: следующий ответ — снова на новую карточку.
            ReviewEvent("card", 1, 10 * Fsrs.DAY, CardState.NEW),
            ReviewEvent("card", 3, 12 * Fsrs.DAY, CardState.LEARNING),
        )
        val optimizer = FsrsOptimizer(events)
        assertEquals("История до и после сброса — разные последовательности", 2, optimizer.cards)
        assertEquals("Первый ответ после сброса не считается повторением", 2, optimizer.trainingItems + optimizer.validationItems)
    }

    @Test
    fun rejectsInvalidAndNegligibleValidationImprovements() {
        fun result(before: Double, after: Double, count: Int = 30) =
            OptimizeOutcome.Done(Fsrs.DEFAULT_W, before, after, 150, 20, count)
        assertFalse(result(0.0, 0.0).improved)
        assertFalse(result(Double.POSITIVE_INFINITY, 0.5).improved)
        assertFalse(result(0.5, Double.NaN).improved)
        assertFalse(result(0.5, 0.49975).improved)
        assertFalse(result(0.5, 0.4, count = 0).improved)
        assertTrue(result(0.5, 0.49).improved)
    }

    @Test
    fun paramsRoundTrip() {
        val encoded = Fsrs.encodeParams(Fsrs.DEFAULT_W)
        val decoded = Fsrs.decodeParams(encoded)!!
        assertEquals(Fsrs.DEFAULT_W.size, decoded.size)
        Fsrs.DEFAULT_W.forEachIndexed { i, v -> assertEquals(v, decoded[i], 1e-4) }
        assertEquals(null, Fsrs.decodeParams("1,2,3"))
    }

    @Test
    fun eveningThenMorningReviewCountsAsOneStudyDay() {
        val hour = 3_600_000L
        val midnight = 1_700_000_000_000L / Fsrs.DAY * Fsrs.DAY
        val events = listOf(
            ReviewEvent("c", 3, midnight + 20 * hour, CardState.NEW),
            ReviewEvent("c", 3, midnight + Fsrs.DAY + 8 * hour, CardState.REVIEW),
        )
        val byHours = FsrsOptimizer(events)
        assertEquals(0, byHours.trainingItems + byHours.validationItems)
        val byDays = FsrsOptimizer(events) { t -> Math.floorDiv(t - 4 * hour, Fsrs.DAY) }
        assertEquals(1, byDays.trainingItems + byDays.validationItems)
    }
}
