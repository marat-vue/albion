package com.marat.albion

import com.marat.albion.data.content.CollectionParser
import com.marat.albion.data.content.XlsxReader
import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.grammar.GrammarPrompt
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the shipped workbook through the same parser and checker as the app. */
class GrammarTaskContentTest {
    private val tasks by lazy {
        val file = File("../../collections/grammar_tasks.xlsx").canonicalFile
        assertTrue("Missing grammar workbook: $file", file.isFile)
        CollectionParser(Json).parseGrammarTasks(file.inputStream().use(XlsxReader::read)).associateBy { it.id }
    }

    private fun task(id: Int) = tasks.getValue("gt:$id")
    private fun verdict(id: Int, answer: String): Verdict {
        val t = task(id)
        val submitted = GrammarPrompt(t.prompt).answerParts(answer)?.joinToString(" / ") ?: answer
        return AnswerCheck.checkGrammar(submitted, t.acceptedAnswers, t.requiresExactWriting, t.commasMatter)
    }
    private fun accepts(id: Int, vararg answers: String) = answers.forEach {
        assertEquals("gt:$id must accept: $it", Verdict.Correct, verdict(id, it))
    }
    private fun rejects(id: Int, vararg answers: String) = answers.forEach {
        assertEquals("gt:$id must reject: $it", Verdict.Wrong, verdict(id, it))
    }

    @Test fun fullAndContractedSentencesAreBothAccepted() {
        accepts(947, "Look! It is raining.", "Look! It's raining.")
        accepts(1036, "She is going to study medicine.", "She's going to study medicine.")
        accepts(797, "It is very cold today.", "It's very cold today.")
        accepts(933, "She is reading a book now.", "She's reading a book now.")
        accepts(1480, "It is windy today.", "It's windy today.")
        accepts(1495, "He is six years old. He is a six-year-old boy.",
            "He's six years old. He's a six-year-old boy.",
            "He is six years old. He's a six-year-old boy.",
            "He's six years old. He is a six-year-old boy.")
    }

    @Test fun negativeQuestionsKeepNotAfterTheSubjectInFullForms() {
        accepts(1444, "isn't it", "is it not")
        accepts(1446, "didn't she", "did she not")
        accepts(1447, "haven't they", "have they not")
        accepts(1448, "aren't I", "am I not")
        accepts(1456, "Aren't you tired?", "Are you not tired?")
        accepts(1457, "Haven't you seen this film?", "Have you not seen this film?",
            "Didn't you see this film?", "Did you not see this film?")
        accepts(1558, "Wouldn't it be better to wait?", "Would it not be better to wait?")
        rejects(1444, "is not it")
        rejects(1446, "did not she")
        rejects(1447, "have not they")
        rejects(1448, "are not I", "amn't I")
        rejects(1456, "Are not you tired?")
        rejects(1457, "Have not you seen this film?", "Did not you see this film?")
        rejects(1558, "Would not it be better to wait?")
    }

    @Test fun reportingParaphrasesAcceptBothTaughtPassivePatterns() {
        accepts(1190, "He is said to be very rich.", "It is said that he is very rich.",
            "He's said to be very rich.", "It's said that he is very rich.",
            "It is said that he's very rich.", "It's said that he's very rich.")
        accepts(1191, "The thief is believed to have escaped through the window.",
            "It is believed that the thief escaped through the window.")
        accepts(1192, "The president is reported to be ill.", "It is reported that the president is ill.")
        rejects(1190, "He is said that he is very rich.")
        rejects(1191, "The thief is believed to escape through the window.")
    }

    @Test fun contextuallyValidAlternativesDoNotLosePoints() {
        accepts(889, "Which", "What")
        accepts(970, "would", "used to")
        accepts(1084, "Could I", "Can I", "May I")
        accepts(1085, "Could you", "Can you", "Would you")
        accepts(1110, "might", "may", "could")
        accepts(1115, "can't have", "cannot have", "couldn't have", "could not have")
        accepts(1260, "call", "calling")
        accepts(1270, "being told", "to be told")
    }

    @Test fun gapAnswersContainOnlyTheMissingWords() {
        accepts(931, "am not working", "'m not working")
        accepts(978, "have lost", "'ve lost")
        accepts(1002, "have been learning", "'ve been learning")
        accepts(1003, "have been repairing", "'ve been repairing")
        rejects(931, "I'm not working", "I am not working")
        rejects(978, "I've lost my keys", "I have lost my keys")
        rejects(1002, "I've been learning English for three years")
        rejects(1003, "I've been repairing the bike")
        val subjectAtEnd = Regex("\\b(I|you|he|she|it|we|they|there)\\s*$", RegexOption.IGNORE_CASE)
        tasks.values.filter { it.kind == "input" }.forEach { t ->
            val parsed = GrammarPrompt(t.prompt)
            if (parsed.gapCount != 1) return@forEach
            val subject = subjectAtEnd.find(parsed.parts.first())?.groupValues?.get(1) ?: return@forEach
            val repeated = Regex("^${Regex.escape(subject)}(?:\\s|['’])", RegexOption.IGNORE_CASE)
            t.acceptedAnswers.forEach { answer ->
                assertFalse("${t.id}: duplicated subject in gap answer: $answer", repeated.containsMatchIn(answer))
            }
        }
    }

    @Test fun shortAnswerAndExplicitContractionExercisesKeepTheirRestrictions() {
        accepts(811, "Yes, I am.")
        accepts(911, "Yes, I am.")
        rejects(811, "Yes, I'm.")
        rejects(911, "Yes, I'm.")
        accepts(813, "Do they have a car?")
        rejects(813, "Do they've a car?")
        accepts(1095, "Did you have to wait long?")
        rejects(1095, "Did you've to wait long?")
        accepts(1578, "Wherever you are, call me.")
        rejects(1578, "Wherever you're, call me.")
        accepts(908, "She is working.")
        rejects(908, "She's working.")
        accepts(909, "She has worked here for years.")
        rejects(909, "She's worked here for years.", "She is worked here for years.")
        accepts(910, "I won't tell anyone.")
        rejects(910, "I will not tell anyone.")
        accepts(689, "It's")
        rejects(689, "It is", "it's", "Its")
    }

    @Test fun grammarErrorsAreStillRejectedAfterAddingAlternatives() {
        rejects(947, "Look! It was raining.", "Look! It is rain.")
        rejects(1036, "She was going to study medicine.", "She are going to study medicine.")
        rejects(1005, "have known", "has been knowing")
        rejects(1110, "must", "mights")
        rejects(1260, "to call")
        rejects(1270, "told", "telling")
        rejects(936, "understand", "am not understanding")
        assertTrue("The negative verb must be specified", task(936).prompt.contains("not / understand"))
    }

    @Test fun wholeSentenceTasksAcceptEquivalentAnswers() {
        accepts(1198, "Where do you have your hair cut?", "Where do you get your hair cut?")
        accepts(1215, "He asked where I worked.", "He asked me where I worked.")
        accepts(1261, "I came to help.", "I've come to help.", "I have come to help.")
        accepts(959, "I was eighteen in 2015.", "I was 18 in 2015.", "In 2015, I was eighteen.")
        accepts(1488, "It was Tom who found the keys.", "It was Tom that found the keys.")
        accepts(1606, "The fall in sales led to financial losses for the company.",
            "The fall in sales led to the company losing money.")
        accepts(1228, "Tom suggested going to the beach.", "Tom suggested that we should go to the beach.")
        accepts(1148, "had told / would have helped", "'d told / 'd have helped")
        rejects(1198, "Where do you cut your hair?")
        rejects(1215, "He asked me where do I work.")
        rejects(1228, "Tom suggested to go to the beach.")
    }

    @Test fun missingCommaFailsOnlyWhereTheCommaIsTheTask() {
        accepts(965, "When I arrived they were having dinner.")
        accepts(1283, "Mr Brown, who comes from Leeds, is our new teacher.")
        rejects(1133, "If it rains the streets get wet.")
        rejects(1283, "Mr Brown who comes from Leeds is our new teacher.")
    }

    @Test fun omittedArticlesCanBeEnteredWithAnOrdinaryKeyboardHyphen() {
        accepts(872, "The / -", "The/—")
        rejects(872, "The / the", "- / The")
    }

    @Test fun shippedAnswerKeysContainNoMechanicalContractionErrors() {
        val badQuestion = Regex("\\b(?:do|does|did|would|could|should|will|is|are|was|were|have|has|had|can|must) not (?:I|you|he|she|it|we|they)\\b", RegexOption.IGNORE_CASE)
        val badHave = Regex("\\b(?:do|does|did) (?:I|you|we|they)['’]ve\\b", RegexOption.IGNORE_CASE)
        val badShortAnswer = Regex("^Yes,\\s+(?:I|you|he|she|it|we|they)['’](?:m|re|s|ve|d|ll)[.!?]?$", RegexOption.IGNORE_CASE)
        tasks.values.forEach { t -> t.acceptedAnswers.forEach { answer ->
            assertFalse("${t.id}: $answer", badQuestion.containsMatchIn(answer))
            assertFalse("${t.id}: $answer", badHave.containsMatchIn(answer))
            assertFalse("${t.id}: $answer", badShortAnswer.matches(answer))
        } }
    }
}
