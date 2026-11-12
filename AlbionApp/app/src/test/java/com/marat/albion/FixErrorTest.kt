package com.marat.albion

import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.games.FixError
import com.marat.albion.data.games.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixErrorTest {
    private fun task(prompt: String, options: String, answer: String, kind: String = "choice") =
        GrammarTaskEntity("gt:1", 1, "A2", 12, "Present Perfect", kind, prompt, options, answer, "Пояснение.")

    @Test fun `choice task becomes a sentence with the wrong option`() {
        val t = FixError.fromGrammar(task("I ___ him since 2010.", "know\nhave known\nknew", "have known")) { it.first() }!!
        assertEquals("I know him since 2010.", t.sentence)
        assertEquals("know", t.wrongText)
        assertEquals("I have known him since 2010.", t.correctSentence)
        assertEquals(Verdict.Correct, t.check("have known"))
        assertEquals(Verdict.Wrong, t.check("knew"))
        val tokens = t.tokens
        assertTrue(t.isError(tokens.first { it.first == "know" }.second))
        assertFalse(t.isError(tokens.first { it.first == "him" }.second))
    }

    @Test fun `unsuitable tasks are skipped`() {
        assertNull("ввод", FixError.fromGrammar(task("I ___ him.", "", "know", kind = "input")))
        assertNull("два пропуска", FixError.fromGrammar(task("I ___ him ___.", "a\nb", "a / b")))
        assertNull("нулевой артикль", FixError.fromGrammar(task("I like ___ music.", "the\n—", "—")))
        assertNull("неверный вариант — пустота", FixError.fromGrammar(task("I play ___ guitar.", "the\n—", "the")))
    }

    @Test fun `typical errors are well formed`() {
        assertTrue(FixError.typical.size >= 25)
        FixError.typical.forEach { t ->
            assertFalse(t.sentence, t.sentence.contains('[') || t.sentence.contains(']'))
            assertTrue(t.sentence, t.wrongText.isNotBlank() && t.corrections.isNotEmpty() && t.explanation.isNotBlank())
            assertTrue(t.sentence, t.tokens.any { t.isError(it.second) })
            assertEquals(Verdict.Correct, t.check(t.corrections.first()))
            assertEquals(Verdict.Wrong, t.check(t.wrongText))
        }
        assertEquals(FixError.typical.size, FixError.typical.map { it.id }.toSet().size)
        assertEquals("I agree with you.", FixError.typical.first().correctSentence)
    }
}
