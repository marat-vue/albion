package com.marat.albion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.conversation.Scenario
import com.marat.albion.data.db.ConversationMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Настоящий прогон через Groq (нужны ключ и VPN): длинная беседа сжимается в пересказ, и собеседник помнит факт из её начала. */
@RunWith(AndroidJUnit4::class)
class ConversationCompactionTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AlbionApp).container

    @Test fun longConversationIsSummarizedAndEarlyFactsSurvive(): Unit = runBlocking {
        assumeTrue("Ключ Groq не добавлен", c.ai.state.value.ready)
        val repo = c.conversations
        val id = repo.create(Scenario.DAY, "B1")
        val dao = c.database.conversations()
        val turns = listOf(
            "Hi! My name is Marat and I live in Kazan." to "Nice to meet you, Marat! What do you do in Kazan?",
            "I work as a software developer. I build mobile apps." to "That sounds exciting! What kind of apps do you build?",
            "Mostly apps for learning English. My dog Bruno sits next to me while I code." to "Bruno sounds like a great coworker! What breed is he?",
        ) + (1..24).map { n ->
            "Today I also did task number $n: I went for a long walk in the park and thought about my plans for the weekend and the next trip." to
                "That sounds like a lovely way to spend time. What else did you think about during walk number $n?"
        }
        var time = 1L
        for ((user, partner) in turns) {
            dao.insert(ConversationMessageEntity(conversationId = id, role = ConversationMessageEntity.USER, text = user, createdAt = time++))
            dao.insert(ConversationMessageEntity(conversationId = id, role = ConversationMessageEntity.ASSISTANT, text = partner, createdAt = time++))
        }
        try { check(repo, id) } finally { repo.delete(id) }
    }

    private suspend fun check(repo: com.marat.albion.data.conversation.ConversationRepository, id: Long) {
        val started = System.currentTimeMillis()
        assertTrue(repo.compactIfNeeded(id))
        val compacted = repo.conversation(id).first()!!
        Log.i("ConversationCompaction", "summary in ${System.currentTimeMillis() - started} ms: ${compacted.summary}")
        assertTrue(compacted.summaryUpTo > 0)
        assertTrue(compacted.summary, compacted.summary.contains("Marat") && compacted.summary.contains("Bruno"))

        repo.send(id, "By the way, do you remember my dog's name and what I do for work?", voice = false)
        val reply = repo.reply(id).last()
        Log.i("ConversationCompaction", "reply: $reply")
        assertTrue(reply, reply.contains("Bruno", ignoreCase = true) || reply.contains("develop", ignoreCase = true) ||
            reply.contains("app", ignoreCase = true))
    }
}
