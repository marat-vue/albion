package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.repo.Leech
import com.marat.albion.data.repo.StudyPlanner
import com.marat.albion.data.repo.StudySource
import com.marat.albion.data.repo.Vocabulary
import com.marat.albion.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeechTest {
    private val now = 1_000_000_000L

    private fun card(i: Int) = CardEntity("w$i", Deck.WORDS.id, i, "word$i", "", "", null, "", "перевод $i", "", "", null, null)
    private fun state(card: CardEntity, lapses: Int, stability: Double) =
        ReviewStateEntity(card.id, card.deck, CardState.REVIEW, 0, stability, 7.0, now - 1, now - 86_400_000L, 10, lapses)

    @Test fun `leech is a word forgotten often that still does not hold`() {
        assertTrue(Leech.isLeech(4, 2.0))
        assertFalse("забывалось мало", Leech.isLeech(3, 0.5))
        assertFalse("уже держится", Leech.isLeech(6, 5.0))
        assertFalse(Leech.isLeech(null))
    }

    @Test fun `session gets at most two leeches, other words untouched`() {
        val cards = (0 until 8).map(::card)
        val states = cards.mapIndexed { i, c -> c.id to if (i < 5) state(c, 5, 1.0) else state(c, 0, 20.0) }.toMap()
        val words = Vocabulary(cards, states)
        val reviews = StudyPlanner.plan(StudySource.Today, words, emptyList(), Settings(), now).reviews
        assertEquals(5, reviews.size)
        assertEquals(Leech.PER_SESSION, reviews.count { Leech.isLeech(states[it.id]) })
        val dictionary = StudyPlanner.plan(StudySource.Dictionary(Deck.WORDS), words, emptyList(), Settings(), now).reviews
        assertEquals(Leech.PER_SESSION, dictionary.count { Leech.isLeech(states[it.id]) })
    }
}
