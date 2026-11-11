package com.marat.albion

import com.marat.albion.data.grammar.OwnAnswer
import com.marat.albion.data.grammar.OwnAnswerLog
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnAnswerLogTest {
    @Test fun appendsCleanEntriesAndMovesRepeatsToTheEnd() {
        var raw = OwnAnswerLog.append(null, "gt:1198", "  Where do you\tget your\nhair cut? ")
        raw = OwnAnswerLog.append(raw, "gt:1215", "He asked me where I worked.")
        raw = OwnAnswerLog.append(raw, "gt:1198", "Where do you get your hair cut?")
        assertEquals(
            listOf(OwnAnswer("gt:1215", "He asked me where I worked."), OwnAnswer("gt:1198", "Where do you get your hair cut?")),
            OwnAnswerLog.parse(raw),
        )
        assertEquals("№1215\tHe asked me where I worked.\n№1198\tWhere do you get your hair cut?", OwnAnswerLog.export(OwnAnswerLog.parse(raw)))
    }

    @Test fun ignoresEmptyAnswersAndBrokenLines() {
        assertEquals("", OwnAnswerLog.append(null, "gt:1", "   "))
        assertEquals(listOf(OwnAnswer("gt:2", "ok")), OwnAnswerLog.parse("broken\n\tno id\ngt:3\t\ngt:2\tok"))
    }

    @Test fun keepsOnlyTheNewestEntries() {
        var raw: String? = null
        repeat(OwnAnswerLog.LIMIT + 5) { raw = OwnAnswerLog.append(raw, "gt:$it", "answer $it") }
        val entries = OwnAnswerLog.parse(raw)
        assertEquals(OwnAnswerLog.LIMIT, entries.size)
        assertEquals("gt:5", entries.first().taskId)
    }
}
