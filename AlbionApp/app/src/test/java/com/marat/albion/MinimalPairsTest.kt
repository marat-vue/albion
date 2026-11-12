package com.marat.albion

import com.marat.albion.data.games.MinimalPairs
import com.marat.albion.data.games.PairSpeech
import com.marat.albion.data.games.PairTask
import com.marat.albion.data.games.PairWord
import com.marat.albion.data.speech.HeardWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MinimalPairsTest {
    private fun w(term: String, ipa: String) = PairWord("words:$term:0", term, ipa)
    private fun heard(vararg words: String) = words.map { HeardWord(it, 0, 10, .9f) }
    private fun terms(pairs: List<com.marat.albion.data.games.MinimalPair>) =
        pairs.map { setOf(it.first.term, it.second.term) }.toSet()

    @Test
    fun `phonemes read long vowels diphthongs and affricates as one sound`() {
        assertEquals(listOf("ʃ", "iː", "p"), MinimalPairs.phonemes("/ʃiːp/"))
        assertEquals(listOf("tʃ", "eɪ", "n", "dʒ"), MinimalPairs.phonemes("/ˈtʃeɪndʒ/"))
        assertEquals(listOf("ə"), MinimalPairs.phonemes("/ə; eɪ/"))
        assertEquals(listOf("g", "əʊ", "l"), MinimalPairs.phonemes("/ɡəʊl/"))
        assertEquals(listOf("ʃ", "iː", "p"), MinimalPairs.phonemes("/ʃi:p/"))
    }

    @Test
    fun `finds classic pairs for Russian speakers`() {
        val pairs = MinimalPairs.find(listOf(
            w("ship", "/ʃɪp/"), w("sheep", "/ʃiːp/"),
            w("think", "/θɪŋk/"), w("sink", "/sɪŋk/"),
            w("west", "/west/"), w("vest", "/vest/"),
            w("full", "/fʊl/"), w("fool", "/fuːl/"),
        ))
        assertEquals(setOf(setOf("ship", "sheep"), setOf("think", "sink"), setOf("west", "vest"), setOf("full", "fool")), terms(pairs))
        assertEquals("iː", pairs.first { it.first.term == "ship" }.contrast.a)
    }

    @Test
    fun `voicing counts only at the end of a word`() {
        val pairs = MinimalPairs.find(listOf(
            w("bad", "/bæd/"), w("bat", "/bæt/"),
            w("down", "/daʊn/"), w("town", "/taʊn/"),
        ))
        assertEquals(setOf(setOf("bad", "bat")), terms(pairs))
    }

    @Test
    fun `skips contrasts that are not hard for Russian speakers and words two sounds apart`() {
        val pairs = MinimalPairs.find(listOf(
            w("lead", "/liːd/"), w("read", "/riːd/"),
            w("cat", "/kæt/"), w("cut", "/kʌt/"), w("cot", "/kɒt/"),
            w("ship", "/ʃɪp/"), w("shop", "/ʃɒp/"), w("sheep", "/ʃiːp/"), w("chip", "/tʃɪp/"),
        ))
        assertEquals(setOf(setOf("cat", "cut"), setOf("cut", "cot"), setOf("ship", "sheep")), terms(pairs))
    }

    @Test
    fun `curated pair is found even when transcriptions differ in length`() {
        val pairs = MinimalPairs.find(listOf(w("walk", "/wɔːk/"), w("work", "/wɜːrk/")))
        assertEquals(setOf(setOf("walk", "work")), terms(pairs))
        assertEquals(setOf("ɔː", "ɜː"), pairs.single().contrast.let { setOf(it.a, it.b) })
    }

    @Test
    fun `phrases duplicate senses and blank transcriptions are ignored`() {
        val pairs = MinimalPairs.find(listOf(
            w("ship", "/ʃɪp/"), PairWord("words:ship:1", "ship", "/ʃɪp/"), w("sheep", "/ʃiːp/"),
            w("give up", "/ɡɪv ʌp/"), w("live", ""),
        ))
        assertEquals(1, pairs.size)
    }

    @Test
    fun `contrast needs exactly one differing sound`() {
        assertNull(MinimalPairs.contrastOf(listOf("ʃ", "ɪ", "p"), listOf("ʃ", "ɪ", "p")))
        assertNull(MinimalPairs.contrastOf(listOf("ʃ", "ɪ", "p"), listOf("s", "iː", "p")))
        assertNotNull(MinimalPairs.contrastOf(listOf("θ", "ɪ", "n"), listOf("f", "ɪ", "n")))
    }

    @Test
    fun `session follows the order uses each word once and respects the count`() {
        val pairs = MinimalPairs.find(listOf(
            w("ship", "/ʃɪp/"), w("sheep", "/ʃiːp/"), w("shop", "/ʃɒp/"),
            w("think", "/θɪŋk/"), w("sink", "/sɪŋk/"),
            w("west", "/west/"), w("vest", "/vest/"),
        ))
        val order = listOf("words:west:0", "words:ship:0", "words:shop:0", "words:think:0", "words:sheep:0")
        val tasks = MinimalPairs.session(pairs, order, count = 2, random = Random(1))
        assertEquals(2, tasks.size)
        assertEquals(listOf(setOf("west", "vest"), setOf("ship", "sheep")), tasks.map { setOf(it.pair.first.term, it.pair.second.term) })
        val ids = tasks.flatMap { listOf(it.pair.first.id, it.pair.second.id) }
        assertEquals(ids.size, ids.toSet().size)
        tasks.forEach { assertTrue(it.target == it.pair.first || it.target == it.pair.second) }
        assertTrue(MinimalPairs.session(pairs, order, count = 0).isEmpty())
    }

    @Test
    fun `interleave keeps the same contrast apart when possible`() {
        val pairs = MinimalPairs.find(listOf(
            w("ship", "/ʃɪp/"), w("sheep", "/ʃiːp/"),
            w("bit", "/bɪt/"), w("beat", "/biːt/"),
            w("think", "/θɪŋk/"), w("sink", "/sɪŋk/"),
        ))
        val tasks = pairs.sortedBy { it.contrast.a }.map { PairTask(it, it.first) }
        val mixed = MinimalPairs.interleave(tasks)
        assertEquals(3, mixed.size)
        assertTrue(mixed.zipWithNext().all { (a, b) -> a.pair.contrast != b.pair.contrast })
    }

    @Test
    fun `judge takes the last attempt and tells target from partner`() {
        val pair = MinimalPairs.find(listOf(w("ship", "/ʃɪp/"), w("sheep", "/ʃiːp/"))).single()
        val task = PairTask(pair, pair.second.takeIf { it.term == "sheep" } ?: pair.first)
        assertEquals(PairSpeech.TARGET, MinimalPairs.judge(heard("Sheep."), task))
        assertEquals(PairSpeech.PARTNER, MinimalPairs.judge(heard("ship"), task))
        assertEquals(PairSpeech.TARGET, MinimalPairs.judge(heard("ship", "sheep"), task))
        assertEquals(PairSpeech.OTHER, MinimalPairs.judge(heard("cheap"), task))
        assertEquals(PairSpeech.NOTHING, MinimalPairs.judge(emptyList(), task))
    }
}
