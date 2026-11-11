package com.marat.albion

import com.marat.albion.data.content.AudioIndex
import com.marat.albion.data.content.CollectionParser
import com.marat.albion.data.content.Deck
import com.marat.albion.data.content.TermRenames
import com.marat.albion.data.content.XlsxReader
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TermRenamesTest {
    @Test fun oldIdsMoveToTheSameMeaningUnderTheNewTerm() {
        val existing = listOf("words:organize#0", "words:organize#1", "words:realize#0", "words:size#0", "words:organise#5")
        val newIds = setOf("words:organise#0", "words:organise#1", "words:size#0")
        assertEquals(
            listOf("words:organize#0" to "words:organise#0", "words:organize#1" to "words:organise#1"),
            TermRenames.moves(Deck.WORDS, existing, newIds),
        )
        // Другие словари не трогаем, даже если термин совпадает.
        assertTrue(TermRenames.moves(Deck.PHRASAL, listOf("phrasal_verbs:organize#0"), setOf("phrasal_verbs:organise#0")).isEmpty())
        // Старый id, который всё ещё есть в таблице, остаётся на месте.
        assertTrue(TermRenames.moves(Deck.WORDS, listOf("words:organize#0"), setOf("words:organize#0", "words:organise#0")).isEmpty())
    }

    @Test fun shippedWordsUseBritishSpellingWithAudio() {
        val root = File("../..").canonicalFile
        val file = File(root, "collections/${Deck.WORDS.file}")
        assumeTrue(file.exists())
        val audio = AudioIndex(File(root, "audio/${Deck.WORDS.audioDir}").list()?.toList().orEmpty())
        val cards = CollectionParser(Json).parseCards(Deck.WORDS, file.inputStream().use(XlsxReader::read), audio)
        val terms = cards.groupBy { it.term }
        TermRenames.forDeck(Deck.WORDS).forEach { (old, new) ->
            assertFalse("в таблице осталось $old", old in terms)
            val renamed = terms[new]
            assertNotNull("нет $new", renamed)
            assertTrue("нет озвучки $new", renamed!!.all { it.audio != null })
        }
        val americanIze = Regex("\\b(?:organ|real|recogn|apolog|emphas|critic|special|summar)iz(?:e|es|ed|ing|er|ation)\\b", RegexOption.IGNORE_CASE)
        cards.forEach { c ->
            listOf(c.term, c.meaningEn, c.example1, c.example2).forEach { text ->
                assertFalse("${c.id}: $text", americanIze.containsMatchIn(text))
            }
        }
    }
}
