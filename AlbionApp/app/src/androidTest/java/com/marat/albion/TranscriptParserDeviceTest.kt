package com.marat.albion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marat.albion.data.listening.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android compiles regexes with ICU, which is stricter than the JVM used by the unit tests. */
@RunWith(AndroidJUnit4::class)
class TranscriptParserDeviceTest {
    @Test fun srtTagsAreStrippedOnTheDevice() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\n<i>Hello</i> {\\an8}there\n\n2\n00:00:03,000 --> 00:00:04,000\nAgain"
        val segments = TranscriptParser.parseSrt(srt).segments
        assertEquals(listOf("Hello there", "Again"), segments.map { it.text })
        assertEquals(1_000L, segments.first().s)
    }

    @Test fun lrcWithOffsetAndWordStampsParsesOnTheDevice() {
        val lrc = "[offset:+100]\n[00:01.00]<00:01.00>One <00:01.50>two\n[00:03.00]Three"
        val segments = TranscriptParser.parseLrc(lrc, durationMs = 5_000).segments
        assertEquals(2, segments.size)
        assertEquals("Three", segments.last().text)
    }
}
