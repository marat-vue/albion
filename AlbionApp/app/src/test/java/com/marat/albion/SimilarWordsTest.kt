package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.repo.SimilarWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarWordsTest {
    private var n = 0
    private fun card(term: String, ru: String = "значение ${n}", ipa: String = "") =
        CardEntity("w:$term#${n++}", Deck.WORDS.id, n, term, ipa, "", null, "", ru, "", "", null, null)

    @Test fun `close spelling, sound or shared translation make words similar`() {
        assertTrue(SimilarWords.similar(card("affect"), card("effect")))
        assertTrue(SimilarWords.similar(card("quite"), card("quiet")))
        assertTrue(SimilarWords.similar(card("big", "большой"), card("large", "крупный; большой")))
        assertTrue(SimilarWords.similar(card("knight", ipa = "/naɪt/"), card("night", ipa = "/naɪt/")))
        assertFalse(SimilarWords.similar(card("cat"), card("dog")))
        assertFalse("короткие слова не сравниваем по буквам", SimilarWords.similar(card("in"), card("on")))
        assertFalse(SimilarWords.similar(card("house"), card("mouse and cat")))
    }

    private fun gaps(order: List<CardEntity>): List<Int> {
        val out = ArrayList<Int>()
        for (i in order.indices) for (j in i + 1 until order.size)
            if (SimilarWords.similar(order[i], order[j])) out += j - i
        return out
    }

    @Test fun `spread separates similar words and keeps everything`() {
        val words = listOf(card("affect"), card("effect"), card("quite"), card("quiet"),
            card("table"), card("river"), card("green"), card("sleep"), card("chair"), card("money"))
        val spread = SimilarWords.spread(words)
        assertEquals(words.map { it.id }.toSet(), spread.map { it.id }.toSet())
        assertEquals(words.size, spread.size)
        assertTrue(gaps(spread).all { it > SimilarWords.SHORT_GAP })
    }

    @Test fun `similar neighbour is pushed back only a few places`() {
        val words = listOf(card("affect"), card("effect")) + (0 until 30).map { card("z".repeat(3) + ('a' + it % 26) + it) }
        val spread = SimilarWords.spread(words)
        val moved = spread.indexOfFirst { it.term == "effect" } - 1
        assertTrue(moved in 0..SimilarWords.MAX_SHIFT)
    }

    @Test fun `queue of only similar words stays in order`() {
        val words = listOf(card("affect"), card("effect"), card("affects"))
        assertEquals(words, SimilarWords.spread(words))
    }
}
