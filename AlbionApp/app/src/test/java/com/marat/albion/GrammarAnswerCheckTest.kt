package com.marat.albion

import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarAnswerCheckTest {
    @Test
    fun rejectsChangesToTenseAgreementAndNegation() {
        val errors = listOf(
            "has escaped" to "had escaped",
            "had worked" to "has worked",
            "have been playing" to "has been playing",
            "does work" to "doesn't work",
            "is going" to "isn't going",
            "walk" to "walks",
            "rises" to "risen",
        )
        errors.forEach { (answer, expected) ->
            assertEquals("$answer вместо $expected", Verdict.Wrong, AnswerCheck.checkGrammar(answer, listOf(expected)))
        }
    }

    @Test
    fun spellingMistakesDoNotEarnGrammarPoints() {
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("swiming", listOf("swimming")))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("writen", listOf("written")))
        // Словарная игра по-прежнему может подсказать, что это опечатка.
        assertEquals(Verdict.Typo, AnswerCheck.check("swiming", listOf("swimming")))
    }

    @Test
    fun acceptsExplicitAlternativesWithHarmlessFormatting() {
        val accepted = listOf("had escaped", "'d escaped")
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("  HAD\t escaped.  ", accepted))
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("’d\u00a0escaped", accepted))
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("‘d escaped", accepted))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("has escaped", accepted))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("'s escaped", accepted))
    }

    @Test
    fun keepsInternalPunctuationAndWordBoundaries() {
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("has, gone", listOf("has gone"), exactWriting = true))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("hasgone", listOf("has gone")))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("its", listOf("it's")))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("were", listOf("we're")))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("is not", listOf("isn't")))
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("is not", listOf("isn't", "is not")))
    }

    @Test
    fun commasAreOptionalOutsideWritingTasks() {
        val accepted = listOf("When I arrived, they were having dinner.")
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("When I arrived they were having dinner", accepted))
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("When I arrived , they were having dinner.", accepted))
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("I like jazz, too", listOf("I like jazz too.")))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("When I arrived they had dinner", accepted))

        val ordinary = task(30, "When I arrived, they were having dinner.")
        assertFalse(ordinary.commasMatter)
        assertEquals(Verdict.Correct, check("When I arrived they were having dinner.", ordinary))

        val commaTask = task(59, "If it rains, the streets get wet.", prompt = "Поставьте запятую, если нужно: If it rains the streets get wet.")
        assertTrue(commaTask.commasMatter)
        assertEquals(Verdict.Wrong, check("If it rains the streets get wet.", commaTask))
        assertEquals(Verdict.Correct, check("if it rains, the streets get wet", commaTask))
        assertTrue(task(123, "It's").commasMatter)
    }

    @Test
    fun modifierLetterApostropheCountsAsApostrophe() {
        assertEquals(Verdict.Correct, AnswerCheck.checkGrammar("Itʼs raining", listOf("It's raining.")))
        assertEquals(Verdict.Correct, check("Itʼs", task(123, "It's")))
        assertEquals(Verdict.Correct, AnswerCheck.check("donʼt", listOf("don't")))
    }

    @Test
    fun writingTopicChecksCaseAndPunctuation() {
        val writing = task(123, "It's")
        assertTrue(writing.requiresExactWriting)
        assertEquals(Verdict.Correct, check("It’s", writing))
        assertEquals(Verdict.Wrong, check("it's", writing))
        assertEquals(Verdict.Wrong, check("Its", writing))
        assertEquals(Verdict.Wrong, check("It's.", writing))

        val punctuation = task(123, "Let's eat, Grandma!")
        assertEquals(Verdict.Wrong, check("Let's eat Grandma!", punctuation))
        assertEquals(Verdict.Wrong, check("Let's eat, Grandma.", punctuation))
        assertEquals(Verdict.Correct, check("Let's eat, Grandma!", punctuation))

        val ordinary = task(33, "has gone / 's gone")
        assertFalse(ordinary.requiresExactWriting)
        assertEquals(Verdict.Correct, check("HAS GONE.", ordinary))
        assertEquals(Verdict.Correct, check("’s gone", ordinary))
    }

    @Test
    fun rejectsEmptyAnswersAndEmptyAcceptedVariants() {
        for (answer in listOf("", " \t\n", ".", "!?")) {
            assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar(answer, listOf("had escaped")))
        }
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("had escaped", emptyList()))
        assertEquals(Verdict.Wrong, AnswerCheck.checkGrammar("had escaped", listOf("", " ")))
    }

    private fun check(answer: String, task: GrammarTaskEntity) =
        AnswerCheck.checkGrammar(answer, task.acceptedAnswers, task.requiresExactWriting, task.commasMatter)

    private fun task(topic: Int, answer: String, prompt: String = "___") = GrammarTaskEntity(
        id = "test:$topic", position = 0, level = "A2", topic = topic, topicTitle = "",
        kind = "input", prompt = prompt, options = "", answer = answer, explanation = "",
    )
}
