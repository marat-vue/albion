package com.marat.albion

import com.marat.albion.data.library.wordCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryTest {

    @Test
    fun bundledDictionaryIsWellFormed() {
        val f = File("src/main/assets/dictionary/eng-rus.tsv")
        val rows = f.readLines().map { it.split('\t') }
        assertTrue("слишком мало статей: ${rows.size}", rows.size > 50_000)
        assertTrue(rows.all { it.size == 3 && it[0].isNotBlank() && it[2].isNotBlank() })
        assertTrue(rows.none { "[[" in it[2] })
        val terms = rows.map { it[0].lowercase() }.toSet()
        listOf("run", "house", "beautiful", "give up", "break the ice", "look forward to").forEach {
            assertTrue("нет слова $it", it in terms)
        }
    }

    @Test
    fun candidatesCoverCommonInflections() {
        assertTrue("study" in wordCandidates("studied"))
        assertTrue("stop" in wordCandidates("stopped"))
        assertTrue("make" in wordCandidates("making"))
        assertTrue("cat" in wordCandidates("Cats"))
        assertTrue("happy" in wordCandidates("happier"))
        assertTrue("children" in wordCandidates("children’s"))
        assertEquals("dog", wordCandidates("“dog”").first())
        assertTrue(wordCandidates("a").isEmpty())
    }

    @Test
    fun candidatesFollowEnglishMorphologyOrder() {
        fun before(word: String, first: String, second: String) {
            val c = wordCandidates(word)
            assertTrue("$word: $c", first in c && second in c && c.indexOf(first) < c.indexOf(second))
        }
        before("notes", "note", "not")
        before("hates", "hate", "hat")
        before("uses", "use", "us")
        before("classes", "class", "classe")
        before("watches", "watch", "watche")
        before("hoped", "hope", "hop")
        before("used", "use", "us")
        before("noting", "note", "not")
        before("making", "make", "mak")
        before("played", "play", "playe")
        before("rained", "rain", "raine")
        before("stopped", "stopp", "stop")
        before("ties", "tie", "ti")
        assertTrue("ty" !in wordCandidates("ties"))
        before("flies", "fly", "flie")
        before("toes", "toe", "to")
        before("tied", "tie", "ti")
    }

    @Test
    fun bundledDictionaryResolvesInflectionsToTheRightHeadword() {
        val terms = File("src/main/assets/dictionary/eng-rus.tsv").readLines().mapTo(HashSet()) { it.substringBefore('\t').lowercase() }
        mapOf(
            "notes" to "note", "hoped" to "hope", "hates" to "hate", "uses" to "use", "using" to "use",
            "noted" to "note", "hoping" to "hope", "stopped" to "stop", "watches" to "watch", "buses" to "bus",
            "ties" to "tie", "later" to "late", "riding" to "ride", "visited" to "visit",
        ).forEach { (form, lemma) ->
            assertEquals(form, lemma, wordCandidates(form).drop(1).firstOrNull { it in terms })
        }
    }
}
