package com.marat.albion

import com.marat.albion.data.listening.Segment
import com.marat.albion.data.listening.TimedWord
import com.marat.albion.data.listening.Transcript
import com.marat.albion.data.listening.TranscriptFormat
import com.marat.albion.data.listening.TranscriptParser
import com.marat.albion.data.listening.locateWords
import com.marat.albion.data.listening.wordRangeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ListeningTranscriptTest {

    private val json = """
        [{"s":1000,"e":2500,"text":"“My aunt will be down presently, Mr. Nuttel,”","words":[
          {"s":1000,"e":1100,"w":"My"},{"s":1100,"e":1400,"w":"aunt"},{"s":1400,"e":1600,"w":"will"},
          {"s":1600,"e":1700,"w":"be"},{"s":1700,"e":1900,"w":"down"},{"s":1900,"e":2200,"w":"presently"},
          {"s":2200,"e":2300,"w":"Mr"},{"s":2300,"e":2500,"w":"Nuttel"}]},
         {"s":3000,"e":4000,"text":"said a young lady.","words":[
          {"s":3000,"e":3300,"w":"said"},{"s":3300,"e":3400,"w":"a"},{"s":3400,"e":3700,"w":"young"},{"s":3700,"e":4000,"w":"lady"}]}]
    """.trimIndent()

    @Test
    fun `json alignment parses segments and words`() {
        val t = TranscriptParser.parse(json)
        assertEquals(2, t.segments.size)
        assertTrue(t.wordLevel)
        assertEquals(4000L, t.durationMs)
        assertEquals("aunt", t.segments[0].words[1].w)
    }

    @Test
    fun `segment search is the last phrase that started`() {
        val t = TranscriptParser.parse(json)
        assertEquals(-1, t.segmentAt(999))
        assertEquals(0, t.segmentAt(1000))
        assertEquals(0, t.segmentAt(2499))
        // В паузе между фразами подсветка остаётся на прозвучавшей.
        assertEquals(0, t.segmentAt(2800))
        assertFalse(t.isInside(0, 2800))
        assertEquals(1, t.segmentAt(3000))
        assertEquals(1, t.segmentAt(99_000))
    }

    @Test
    fun `word search inside a phrase`() {
        val t = TranscriptParser.parse(json)
        assertEquals(-1, t.wordAt(0, 999))
        assertEquals(0, t.wordAt(0, 1050))
        assertEquals(1, t.wordAt(0, 1100))
        assertEquals(7, t.wordAt(0, 2499))
        assertEquals(2, t.wordAt(1, 3500))
        assertEquals(-1, t.wordAt(5, 3500))
    }

    @Test
    fun `binary search agrees with a linear scan on a long transcript`() {
        val segments = (0 until 500).map { i ->
            val s = i * 1_000L
            Segment(s, s + 800, "word$i next$i", listOf(TimedWord(s, s + 400, "word$i"), TimedWord(s + 400, s + 800, "next$i")))
        }
        val t = Transcript(segments)
        for (ms in listOf(0L, 399L, 400L, 850L, 123_456L, 499_799L, 600_000L)) {
            val linear = segments.indexOfLast { it.s <= ms }
            assertEquals(linear, t.segmentAt(ms))
            val words = segments[linear].words
            assertEquals(words.indexOfLast { it.s <= ms }, t.wordAt(linear, ms))
        }
    }

    @Test
    fun `words are located in the text ignoring punctuation and case`() {
        val text = "“My aunt will be down presently, Mr. Nuttel,”"
        val ranges = locateWords(text, listOf("my", "aunt", "presently", "Mr", "Nuttel", "missing"))
        assertEquals("My", text.substring(ranges[0]!!))
        assertEquals("presently", text.substring(ranges[2]!!))
        assertEquals("Nuttel", text.substring(ranges[4]!!))
        assertNull(ranges[5])
    }

    @Test
    fun `short words are not found inside longer ones`() {
        val text = "Nuttel had a letter"
        val ranges = locateWords(text, listOf("Nuttel", "had", "a", "letter"))
        assertEquals(11..11, ranges[2])
    }

    @Test
    fun `curly apostrophes match straight ones`() {
        val text = "It’s my aunt’s house"
        val ranges = locateWords(text, listOf("It's", "aunt's"))
        assertEquals("It’s", text.substring(ranges[0]!!))
        assertEquals("aunt’s", text.substring(ranges[1]!!))
    }

    @Test
    fun `tapped word under offset`() {
        val text = "“Nuttel,” said self-possessed Vera."
        assertEquals("Nuttel", text.substring(wordRangeAt(text, 3)!!))
        assertEquals("self-possessed", text.substring(wordRangeAt(text, 17)!!))
        assertNull(wordRangeAt(text, 0))
        assertNull(wordRangeAt(text, 8))
    }

    @Test
    fun `srt is parsed into phrase-level segments`() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i> there,\r\nold friend.\r\n\r\n" +
            "2\n00:00:04,200 --> 00:00:06,000\nHow are you?\n\n3\n00:00:07,000 --> 00:00:07,500\n\n"
        assertEquals(TranscriptFormat.SRT, TranscriptParser.detect(srt))
        val t = TranscriptParser.parse(srt)
        assertEquals(2, t.segments.size)
        assertEquals(Segment(1000, 3500, "Hello there, old friend."), t.segments[0])
        assertEquals(4200L, t.segments[1].s)
        assertFalse(t.wordLevel)
    }

    @Test
    fun `lrc lines end where the next one starts`() {
        val lrc = "[ti:Story]\n[00:01.50]First line\n[00:04.00]Second line\n[00:07.25]Last line"
        assertEquals(TranscriptFormat.LRC, TranscriptParser.detect(lrc))
        val t = TranscriptParser.parse(lrc, durationMs = 10_000)
        assertEquals(listOf(1500L, 4000L, 7250L), t.segments.map { it.s })
        assertEquals(listOf(4000L, 7250L, 10_000L), t.segments.map { it.e })
    }

    @Test
    fun `lrc repeated stamps and offset`() {
        val lrc = "[offset:500]\n[00:10.00][00:02.00]Chorus"
        val t = TranscriptParser.parse(lrc)
        assertEquals(listOf(1500L, 9500L), t.segments.map { it.s })
    }

    @Test
    fun `enhanced lrc gives word timing`() {
        val lrc = "[00:01.00]<00:01.00>Hello <00:01.60>old <00:02.10>friend\n[00:03.00]Next"
        val t = TranscriptParser.parse(lrc)
        assertTrue(t.wordLevel)
        assertEquals("Hello old friend", t.segments[0].text)
        assertEquals(listOf(1000L, 1600L, 2100L), t.segments[0].words.map { it.s })
        assertEquals(3000L, t.segments[0].words.last().e)
    }

    @Test
    fun `cleaning sorts phrases and drops empty ones`() {
        val raw = """[{"s":5000,"e":4000,"text":"late"},{"s":1000,"e":2000,"text":"  "},{"s":0,"e":900,"text":"early"}]"""
        val t = TranscriptParser.parse(raw)
        assertEquals(listOf("early", "late"), t.segments.map { it.text })
        assertEquals(5000L, t.segments[1].e)
    }

    @Test
    fun `detect by file name wins`() {
        assertEquals(TranscriptFormat.LRC, TranscriptParser.detect("[{}]", "song.lrc"))
        assertEquals(TranscriptFormat.JSON, TranscriptParser.detect("[{\"s\":0}]"))
    }

    /** Встроенные треки (listening/<id>/) должны разбираться и быть пословными. */
    @Test
    fun `bundled tracks parse`() {
        val root = File("../../listening").canonicalFile
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in dirs) {
            assertTrue("${dir.name}: нет track.json", File(dir, "track.json").exists())
            assertTrue("${dir.name}: нет audio.mp3", File(dir, "audio.mp3").exists())
            val t = TranscriptParser.parse(File(dir, "transcript.json").readText())
            assertTrue(dir.name, t.segments.size > 10 && t.wordLevel)
            // Каждое слово разметки нашлось в тексте своей фразы — иначе подсветка «прыгает».
            t.segments.indices.forEach { i ->
                val missing = t.wordRanges(i).count { it == null }
                assertEquals("${dir.name} #$i: ${t.segments[i].text}", 0, missing)
            }
        }
    }
}
