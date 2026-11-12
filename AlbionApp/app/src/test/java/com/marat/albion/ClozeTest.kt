package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Memory
import com.marat.albion.data.games.Cloze
import com.marat.albion.data.games.ClozeTask
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.repo.CardFormat
import com.marat.albion.data.repo.ReviewFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeTest {
    @Test fun `finds the word with its ending and hides every occurrence`() {
        val t = Cloze.make("She looked at me and I looked away.", "look")!!
        assertEquals("looked", t.answer)
        assertEquals("She ${ClozeTask.GAP} at me and I ${ClozeTask.GAP} away.", t.masked)
        assertEquals("studies", Cloze.make("He studies law in London.", "study")!!.answer)
        assertEquals("stopped", Cloze.make("The rain stopped at noon.", "stop")!!.answer)
        assertEquals("making", Cloze.make("They are making a cake.", "make")!!.answer)
        assertNull("неправильную форму не угадываем", Cloze.make("He gave up smoking.", "give up"))
        assertEquals("giving up", Cloze.make("She is giving up sugar.", "give up")!!.answer)
    }

    @Test fun `does not match other words that merely start the same`() {
        assertNull(Cloze.make("This tool is really useful today.", "use"))
        assertNull(Cloze.make("Nothing to see here at all.", "look"))
        assertNull("плейсхолдер", Cloze.make("Don't pull my leg, please.", "pull someone's leg"))
    }

    @Test fun `right word in the wrong form is almost right`() {
        val t = Cloze.make("She looked at me for a while.", "look")!!
        assertEquals(Verdict.Correct, Cloze.check("looked", t, "look"))
        assertEquals(Verdict.Typo, Cloze.check("look", t, "look"))
        assertEquals(Verdict.Wrong, Cloze.check("see", t, "look"))
    }

    private fun card(deck: Deck = Deck.WORDS, example: String = "She looked at me for a while.") =
        CardEntity("w:look#1", deck.id, 0, "look", "", "", null, "", "смотреть", example, "", null, null)

    private fun review(stability: Double) = Memory(state = CardState.REVIEW, stability = stability, difficulty = 5.0, due = 0, lastReview = 0, reps = 5)

    @Test fun `format follows maturity`() {
        assertEquals(CardFormat.Classic, ReviewFormat.choose(card(), review(3.0), 1))
        assertEquals(CardFormat.Cloze, ReviewFormat.choose(card(), review(8.0), 1))
        assertEquals(CardFormat.Classic, ReviewFormat.choose(card(example = "Nothing here at all."), review(8.0), 1))
        val mature = (0L until 30L).map { ReviewFormat.choose(card(), review(30.0), it) }
        assertTrue(CardFormat.Typed in mature && CardFormat.Cloze in mature && CardFormat.Classic !in mature)
        assertTrue(mature.count { it == CardFormat.Typed } > mature.count { it == CardFormat.Cloze })
        assertEquals("в один день формат не меняется", ReviewFormat.choose(card(), review(30.0), 5), ReviewFormat.choose(card(), review(30.0), 5))
        assertEquals(CardFormat.Classic, ReviewFormat.choose(card(Deck.IRREGULAR), review(30.0), 1))
        assertEquals(CardFormat.Classic, ReviewFormat.choose(card(), Memory(), 1))
        assertNotNull(Cloze.make(card()))
    }
}
