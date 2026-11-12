package com.marat.albion

import com.marat.albion.data.games.Verdict
import com.marat.albion.data.library.WordMarker
import com.marat.albion.data.listening.Dictation
import com.marat.albion.data.listening.ListeningCloze
import com.marat.albion.data.listening.Segment
import com.marat.albion.data.listening.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ListeningPracticeTest {

    @Test
    fun `dictation ignores case punctuation and quotes`() {
        val r = Dictation.check("my aunt will be down presently mr nuttel", "“My aunt will be down presently, Mr. Nuttel,”")
        assertEquals(1f, r.accuracy)
        assertTrue(r.passed)
        assertTrue(r.mask.none { it })
    }

    @Test
    fun `dictation keeps apostrophes and hyphens inside words`() {
        assertEquals("It's a self-possessed girl", Dictation.forDictation("“It’s a self-possessed girl —”"))
        assertTrue(Dictation.check("its a self possessed girl", "It’s a self-possessed girl").accuracy > 0.85f)
    }

    @Test
    fun `missing word is marked and fails`() {
        val r = Dictation.check("my aunt will be presently", "My aunt will be down presently")
        assertFalse(r.passed)
        val marked = r.expected.indices.filter { r.mask[it] }.map { r.expected[it] }.joinToString("")
        assertEquals("down", marked)
    }

    @Test
    fun `a missing article fails even in a long phrase`() {
        val r = Dictation.check("she walked slowly towards window of the old house", "She walked slowly towards the window of the old house")
        assertTrue(r.accuracy >= 0.9f)
        assertFalse(r.passed)
        assertEquals(listOf("the"), r.missedWords)
    }

    @Test
    fun `small typo still passes`() {
        val r = Dictation.check("my aunt will be down presentlly", "My aunt will be down presently")
        assertTrue(r.passed)
    }

    @Test
    fun `typing extra words is penalised`() {
        val r = Dictation.check("my aunt will be down presently and many more words here", "My aunt will be down presently")
        assertFalse(r.passed)
    }

    @Test
    fun `empty answer shows the text with everything marked`() {
        val r = Dictation.check("", "Romance at short notice")
        assertEquals(0f, r.accuracy)
        assertTrue(r.expected.indices.filter { r.expected[it] != ' ' }.all { r.mask[it] })
    }

    private val transcript = Transcript(listOf(
        Segment(0, 1000, "She studied the weather."),
        Segment(1000, 2000, "Nothing to see here."),
        Segment(2000, 3000, "He gave up smoking at last."),
        Segment(3000, 4000, "The weather was fine."),
    ))

    @Test
    fun `cloze blanks only collection words in their spoken form`() {
        val marker = WordMarker(listOf("study", "give up", "weather"), mapOf("gave" to "give"))
        val items = ListeningCloze.build(transcript, marker, limit = 10, random = Random(1))
        assertEquals(listOf(0, 2, 3), items.map { it.segment })
        assertTrue(items.first { it.segment == 2 }.answer == "gave up")
        items.forEach { assertEquals(it.text, it.before + it.answer + it.after) }
    }

    @Test
    fun `cloze respects the limit and empty collections`() {
        val marker = WordMarker(listOf("weather"))
        assertEquals(1, ListeningCloze.build(transcript, marker, limit = 1, random = Random(2)).size)
        assertTrue(ListeningCloze.build(transcript, WordMarker(emptyList())).isEmpty())
    }

    @Test
    fun `cloze answer check allows a typo`() {
        val item = ListeningCloze.build(transcript, WordMarker(listOf("weather")), random = Random(3)).first()
        assertEquals(Verdict.Correct, ListeningCloze.check("Weather", item))
        assertEquals(Verdict.Typo, ListeningCloze.check("wether", item))
        assertEquals(Verdict.Wrong, ListeningCloze.check("rain", item))
    }
}
