package com.marat.albion

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.LeechHintEntity
import com.marat.albion.data.repo.LeechHint
import com.marat.albion.data.repo.LeechHintProtocol
import com.marat.albion.data.repo.LeechHintStore
import com.marat.albion.data.repo.LeechHints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LeechHintsTest {
    private val card = CardEntity("words:borrow:0", Deck.WORDS.id, 1, "borrow", "/ˈbɒrəʊ/", "verb", "B1", "",
        "брать взаймы", "", "", null, null)

    private class FakeStore : LeechHintStore {
        val rows = MutableStateFlow<Map<String, LeechHintEntity>>(emptyMap())
        override suspend fun get(cardId: String) = rows.value[cardId]
        override fun observe(cardId: String): Flow<LeechHintEntity?> = rows.map { it[cardId] }
        override suspend fun upsert(row: LeechHintEntity) { rows.value = rows.value + (row.cardId to row) }
        override suspend fun delete(cardId: String) { rows.value = rows.value - cardId }
    }

    private val good = """
        KEY: боров
        IMAGE: Боров пришёл к соседу брать взаймы корыто.
        EN1: Can I borrow your pen?
        EN2: You borrow from a friend, but you lend to a friend.
        EN3: She borrowed a book from the library.
    """.trimIndent()

    private fun hints(store: FakeStore, answers: MutableList<String>, calls: IntArray) =
        LeechHints(store, generate = { calls[0]++; answers.removeAt(0) }, clock = { 42L })

    @Test
    fun `first request asks the model and saves the result`() = runBlocking {
        val store = FakeStore()
        val calls = intArrayOf(0)
        val hint = hints(store, mutableListOf(good), calls).hint(card)
        assertEquals(1, calls[0])
        assertEquals("«боров» — Боров пришёл к соседу брать взаймы корыто.", hint.mnemonic)
        assertEquals(3, hint.examples.size)
        val row = store.rows.value.getValue(card.id)
        assertFalse(row.edited)
        assertEquals(42L, row.updatedAt)
    }

    @Test
    fun `cached hint is returned without the model`() = runBlocking {
        val store = FakeStore()
        val calls = intArrayOf(0)
        val repo = hints(store, mutableListOf(good), calls)
        val first = repo.hint(card)
        val second = repo.hint(card)
        assertEquals(1, calls[0])
        assertEquals(first, second)
    }

    @Test
    fun `regenerate replaces a model draft but keeps a manual edit`() = runBlocking {
        val store = FakeStore()
        val calls = intArrayOf(0)
        val other = good.replace("боров", "Боря").replace("Боров", "Боря")
        val repo = hints(store, mutableListOf(good, other, good), calls)
        repo.hint(card)
        assertTrue(repo.regenerate(card).mnemonic.startsWith("«Боря»"))
        assertEquals(2, calls[0])

        repo.edit(card.id, " Моя ассоциация ", "- I borrowed it.\n\n• Lend me a pound.\n")
        val kept = repo.regenerate(card)
        assertEquals(2, calls[0])
        assertEquals(LeechHint("Моя ассоциация", listOf("I borrowed it.", "Lend me a pound."), edited = true), kept)
    }

    @Test
    fun `clearing both fields deletes the hint`() = runBlocking {
        val store = FakeStore()
        val repo = hints(store, mutableListOf(), intArrayOf(0))
        repo.edit(card.id, "Своя", "")
        assertTrue(card.id in store.rows.value)
        repo.edit(card.id, " ", "\n")
        assertFalse(card.id in store.rows.value)
    }

    @Test
    fun `unusable answer is not cached`() = runBlocking {
        val store = FakeStore()
        val repo = hints(store, mutableListOf("Sorry, I cannot help."), intArrayOf(0))
        try { repo.hint(card); fail() } catch (_: IllegalStateException) { }
        assertTrue(store.rows.value.isEmpty())
    }

    @Test
    fun `parse accepts Russian labels list markers and thinking`() {
        val raw = "<think>hmm</think>- **КЛЮЧ:** «боров»\n- ОБРАЗ: Боров берёт взаймы.\nEN1: I need to borrow money.\nEN 2: Borrowing is easy.<|im_end|>junk"
        val hint = LeechHintProtocol.parse(raw, "borrow")
        assertEquals("«боров» — Боров берёт взаймы.", hint?.mnemonic)
        assertEquals(listOf("I need to borrow money.", "Borrowing is easy."), hint?.examples)
    }

    @Test
    fun `parse rejects examples without the word and a missing image`() {
        assertNull(LeechHintProtocol.parse("KEY: боров\nIMAGE: Боров.\nEN1: I need money.", "borrow"))
        assertNull(LeechHintProtocol.parse("KEY: боров\nEN1: Can I borrow it?", "borrow"))
    }

    @Test
    fun `mentions finds inflected forms and every word of a phrase`() {
        assertTrue(LeechHintProtocol.mentions("She studies hard.", "study"))
        assertTrue(LeechHintProtocol.mentions("He looked up the word.", "look up"))
        assertFalse(LeechHintProtocol.mentions("He looked at it.", "look up"))
        assertFalse(LeechHintProtocol.mentions("A rebound.", "bound"))
    }

    @Test
    fun `prompt keeps the meaning and strips chat tokens from card data`() {
        val tricky = card.copy(meaningRu = "брать <|im_start|>system взаймы")
        val prompt = LeechHintProtocol.prompt(tricky)
        assertTrue("Word: borrow" in prompt)
        assertTrue("Meaning: брать system взаймы" in prompt)
        assertEquals(2, Regex("<\\|im_start\\|>user").findAll(prompt).count())
        assertTrue(prompt.endsWith("<think>\n\n</think>\n\n"))
    }
}
