package com.marat.albion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marat.albion.data.listening.TranscriptFormat
import com.marat.albion.data.listening.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android compiles regexes with ICU, which is stricter than the JVM used by the unit tests. */
@RunWith(AndroidJUnit4::class)
class TranscriptParserDeviceTest {
    @Test fun srtTagsAreStrippedOnTheDevice() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\n<i>Hello</i> {\\an8}there\n\n2\n00:00:03,000 --> 00:00:04,000\nAgain"
        assertEquals(TranscriptFormat.SRT, TranscriptParser.detect(srt))
        val segments = TranscriptParser.parseSrt(srt).segments
        assertEquals(listOf("Hello there", "Again"), segments.map { it.text })
        assertEquals(1_000L, segments.first().s)
    }

    @Test fun lrcOffsetAndWordStampsApplyOnTheDevice() {
        // A positive offset moves the lyrics earlier.
        val lrc = "[offset:+100]\n[00:01.00]<00:01.00>One <00:01.50>two\n[00:03.00]Three"
        assertEquals(TranscriptFormat.LRC, TranscriptParser.detect(lrc))
        val segments = TranscriptParser.parseLrc(lrc, durationMs = 5_000).segments
        assertEquals(listOf("One two", "Three"), segments.map { it.text })
        assertEquals(listOf(900L, 2_900L), segments.map { it.s })
        assertEquals(listOf("One", "two"), segments.first().words.map { it.w })
    }

    @Test fun jsonTranscriptIsDetectedOnTheDevice() {
        val json = """[ {"s":0,"e":1200,"text":"Hello there"}, {"s":1200,"e":2000,"text":"Again"} ]"""
        assertEquals(TranscriptFormat.JSON, TranscriptParser.detect(json))
        assertEquals(TranscriptFormat.JSON, TranscriptParser.detect("[]"))
        assertEquals(listOf("Hello there", "Again"), TranscriptParser.parse(json).segments.map { it.text })
    }
}
