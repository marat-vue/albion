package com.marat.albion

import com.marat.albion.data.grammar.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GrammarLearningTest {
    private fun topic(n: Int, level: String = "A1", dependencies: List<Int> = emptyList()) =
        Topic("lesson-$n", n, "Lesson $n", level, TopicKind.Lesson, prerequisites = dependencies)

    @Test fun recommendationFillsMissingPrerequisitesAndThenMovesOn() {
        val topics = listOf(topic(1), topic(2, dependencies = listOf(4)), topic(3), topic(4, "A2", listOf(1)))
        assertEquals(listOf(1, 4, 2, 3), GrammarLearningPath.ordered(topics).map { it.number })
        assertEquals(4, GrammarLearningPath.recommend(topics, setOf("lesson-1"), "A1")?.number)
        assertEquals(2, GrammarLearningPath.recommend(topics, setOf("lesson-1", "lesson-4"), "A1")?.number)
        assertEquals(3, GrammarLearningPath.recommend(topics, setOf("lesson-1", "lesson-2", "lesson-4"), "A1", "lesson-2")?.number)
        assertNull(GrammarLearningPath.recommend(topics, topics.map { it.id }.toSet()))
    }

    @Test fun searchFindsExamplesAndRussianAliasesWithoutExposingAnswers() {
        val doc = MarkdownParser.parse("""
            # Perfect
            > [!abstract] Коротко
            > A present result.
            ## Примеры
            I've lost my key.
            ## Проверь себя
            1. A question.
            > [!success]- Ответы
            > Secret answer.
        """.trimIndent())
        val t = topic(33).copy(keywords = "перфект, совершённое", searchText = GrammarSearch.text(doc))
        assertTrue(GrammarSearch.matches(t, "lost key"))
        assertTrue(GrammarSearch.matches(t, "совершенное"))
        assertTrue(GrammarSearch.matches(t, "033"))
        assertFalse(GrammarSearch.matches(t, "secret answer"))
        assertTrue(GrammarSearch.snippet(t, "lost").contains("lost"))
    }

    @Test fun searchSnippetHidesHighlightAndStrikethroughMarkup() {
        val doc = MarkdownParser.parse("""
            # Cause
            We stayed at home ==because== it was raining, not ~~for~~ the cold.
        """.trimIndent())
        val snippet = GrammarSearch.snippet(topic(116).copy(searchText = GrammarSearch.text(doc)), "because")
        assertTrue(snippet, snippet.contains("at home because it was raining, not for the cold"))
        assertFalse(snippet, snippet.contains("==") || snippet.contains("~~"))
    }

    @Test fun actualCourseHasValidAcyclicPrerequisitesAndSixExercisesPerLesson() {
        val dir = File("../../grammar").canonicalFile
        assumeTrue(dir.isDirectory)
        val lessons = dir.listFiles().orEmpty().filter { Regex("^(?!000)\\d{3} - ").containsMatchIn(it.name) }.map { file ->
            val doc = MarkdownParser.parse(file.readText())
            assertEquals(file.name, 6, doc.blocks.filterIsInstance<MdList>().first { it.ordered }.items.size)
            assertTrue(file.name, doc.blocks.any { it is MdCallout && it.kind == "success" && it.foldable && !it.initiallyExpanded })
            topic(file.name.take(3).toInt(), doc.frontmatter.getValue("level"),
                doc.frontmatter["prerequisites"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() })
        }
        assertEquals(144, lessons.size)
        val ordered = GrammarLearningPath.ordered(lessons).map { it.number }
        lessons.forEach { lesson -> lesson.prerequisites.forEach { prerequisite ->
            assertTrue("${lesson.number} needs $prerequisite", prerequisite in ordered)
            assertTrue("cycle/order: ${lesson.number} needs $prerequisite", ordered.indexOf(prerequisite) < ordered.indexOf(lesson.number))
        } }
    }
}
