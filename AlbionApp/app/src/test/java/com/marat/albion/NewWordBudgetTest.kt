package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.db.CollectionWordEntity
import com.marat.albion.data.db.DayAnswer
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.repo.NewWordBudget
import com.marat.albion.data.repo.StudyLoad
import com.marat.albion.data.repo.StudyPlanner
import com.marat.albion.data.repo.StudySource
import com.marat.albion.data.repo.Vocabulary
import com.marat.albion.data.repo.WordCards
import com.marat.albion.data.settings.NewCardOrder
import com.marat.albion.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NewWordBudgetTest {
    private val day = 20_000L * Fsrs.DAY

    @Test fun `without history the budget equals the setting`() {
        val b = NewWordBudget.compute(20, StudyLoad(day), listOf(10, 10, 10))
        assertEquals(20, b.limit)
        assertNull(b.reason)
    }

    @Test fun `weak weekly retention cuts new words in steps`() {
        assertEquals(15, NewWordBudget.compute(20, StudyLoad(day, 100, 80), emptyList()).limit)
        assertEquals(10, NewWordBudget.compute(20, StudyLoad(day, 100, 70), emptyList()).limit)
        assertEquals(20, NewWordBudget.compute(20, StudyLoad(day, 100, 90), emptyList()).limit)
        // Маленькая выборка ничего не решает.
        assertEquals(20, NewWordBudget.compute(20, StudyLoad(day, 10, 1), emptyList()).limit)
        assertNotNull(NewWordBudget.compute(20, StudyLoad(day, 100, 70), emptyList()).reason)
    }

    @Test fun `heavy forecast reduces linearly but keeps a floor`() {
        // Цель — 20 × 8 = 160 повторений в день.
        assertEquals(20, NewWordBudget.compute(20, StudyLoad(day), listOf(160, 160, 160)).limit)
        assertEquals(10, NewWordBudget.compute(20, StudyLoad(day), listOf(240, 240, 240)).limit)
        assertEquals(NewWordBudget.FLOOR, NewWordBudget.compute(20, StudyLoad(day), listOf(500, 500, 500)).limit)
        assertEquals(1, NewWordBudget.compute(1, StudyLoad(day), listOf(500)).limit)
        assertEquals(0, NewWordBudget.compute(0, StudyLoad(day), emptyList()).limit)
    }

    @Test fun `forecast counts tomorrow with leftovers and then per day`() {
        fun s(due: Long, state: Int = CardState.REVIEW) = ReviewStateEntity("$due", "w", state, 0, 5.0, 5.0, due, 0, 1, 0)
        val states = listOf(
            s(day - Fsrs.DAY), s(day + 1), s(day + Fsrs.DAY + 5),   // просрочено, сегодня, завтра → завтра
            s(day + 2 * Fsrs.DAY), s(day + 3 * Fsrs.DAY + 1),      // послезавтра, через три дня
            s(day + 4 * Fsrs.DAY), s(day, CardState.LEARNING),      // за горизонтом; учебный шаг
        )
        assertEquals(listOf(3, 1, 1), NewWordBudget.forecast(states, day))
    }

    private val settings = Settings(newPerDay = 4, reviewsPerDay = 100, newOrder = NewCardOrder.FILE)
    private fun card(id: String, position: Int) = CardEntity(id, Deck.WORDS.id, position, id, "", "", null, "", "п$id", "", "", null, null)

    @Test fun `today shares one budget across all collections`() {
        val cards = (0 until 10).map { card("w$it", it) }
        val a = CollectionEntity(1, "A", 0)
        val b = CollectionEntity(2, "B", 1)
        val members = cards.mapIndexed { i, c -> CollectionWordEntity(if (i < 5) 1 else 2, c.deck, WordCards.key(c.term), 0) }
        val words = Vocabulary(cards, emptyMap(), listOf(a, b), members)
        assertEquals(4, StudyPlanner.plan(StudySource.Today, words, emptyList(), settings, day).news.size)
        // Три новых уже начаты сегодня где угодно — осталось одно и в «Сегодня», и в коллекции.
        val answered = (0 until 3).map { DayAnswer("w$it", Deck.WORDS.id, CardState.NEW) }
        assertEquals(1, StudyPlanner.plan(StudySource.Today, words, answered, settings, day).news.size)
        assertEquals(1, StudyPlanner.plan(StudySource.Collection(2), words, answered, settings, day).news.size)
        // Слабое удержание — бюджет вдвое меньше.
        val weak = StudyLoad(day, 50, 30)
        assertEquals(2, StudyPlanner.plan(StudySource.Today, words, emptyList(), settings, day, weak).news.size)
        assertNotNull(StudyPlanner.overview(words, emptyList(), settings, day, weak).today.newNote)
    }
}
