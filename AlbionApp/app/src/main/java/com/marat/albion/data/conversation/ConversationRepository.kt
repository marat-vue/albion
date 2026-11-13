package com.marat.albion.data.conversation

import com.marat.albion.data.ai.GroqClient
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.ConversationEntity
import com.marat.albion.data.db.ConversationMessageEntity
import com.marat.albion.data.library.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Разговорная практика: история бесед в Room, ответы, разбор ошибок и подсказки — через Groq. */
class ConversationRepository(database: AlbionDatabase, private val groq: GroqClient, private val translator: Translator) {
    private val dao = database.conversations()
    val status get() = groq.status

    fun summaries() = dao.observeSummaries()
    fun conversation(id: Long) = dao.observe(id)
    fun messages(id: Long) = dao.observeMessages(id)

    suspend fun create(scenario: Scenario, level: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(ConversationEntity(title = scenario.title, scenario = scenario.id, level = level,
            createdAt = now, updatedAt = now))
        // Первая реплика заготовлена: беседа начинается сразу, без запроса к модели.
        dao.insert(ConversationMessageEntity(conversationId = id, role = ConversationMessageEntity.ASSISTANT,
            text = scenario.opener, createdAt = now))
        return id
    }

    suspend fun send(conversationId: Long, text: String, voice: Boolean): Long {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Напишите сообщение." }
        require(clean.length <= 1_200) { "Сообщение слишком длинное. Разбейте его на несколько реплик." }
        val now = System.currentTimeMillis()
        val conversation = dao.get(conversationId) ?: error("Беседа удалена.")
        val first = dao.messages(conversationId).none { it.fromUser }
        val id = dao.insert(ConversationMessageEntity(conversationId = conversationId, role = ConversationMessageEntity.USER,
            text = clean, voice = voice, createdAt = now))
        dao.touch(conversationId, now)
        // Свободная беседа называется по первой фразе ученика, ролевая — по ситуации.
        if (first && conversation.scenario == Scenario.FREE.id) dao.rename(conversationId, clean.lineSequence().first().take(48))
        return id
    }

    /** Ответ собеседника на последнюю реплику ученика: поток растущего текста, затем сохранение. */
    fun reply(conversationId: Long): Flow<String> = flow {
        val conversation = dao.get(conversationId) ?: error("Беседа удалена.")
        val all = dao.messages(conversationId)
        check(all.lastOrNull()?.fromUser == true) { "Сначала напишите реплику." }
        // Пересказанные реплики заменяет их краткое содержание в системной подсказке.
        val history = all.filter { it.id > conversation.summaryUpTo }
        val prompt = ConversationProtocol.reply(Scenario.of(conversation.scenario), conversation.level, history, conversation.summary)
        var text = ""
        groq.stream(prompt, ConversationProtocol.REPLY_TOKENS, temperature = 0.8f).collect { raw ->
            text = ConversationProtocol.cleanReply(raw)
            if (text.isNotBlank()) emit(text)
        }
        check(text.isNotBlank()) { "Модель не ответила. Попробуйте ещё раз." }
        val now = System.currentTimeMillis()
        dao.insert(ConversationMessageEntity(conversationId = conversationId, role = ConversationMessageEntity.ASSISTANT,
            text = text, createdAt = now))
        dao.touch(conversationId, now)
    }

    /**
     * Пересказывает самые старые реплики, если история перестала помещаться в бюджет подсказки.
     * Вызывается после ответа и разбора, поэтому не задерживает собеседника. true — беседа сжата.
     */
    suspend fun compactIfNeeded(conversationId: Long): Boolean {
        val conversation = dao.get(conversationId) ?: return false
        val history = dao.messages(conversationId).filter { it.id > conversation.summaryUpTo }
        val count = ConversationProtocol.compactionSize(history)
        if (count == 0) return false
        val chunk = history.take(count)
        val raw = groq.complete(ConversationProtocol.summary(Scenario.of(conversation.scenario), conversation.summary, chunk),
            ConversationProtocol.SUMMARY_TOKENS)
        val summary = ConversationProtocol.cleanSummary(raw)
        // Пустой пересказ потерял бы реплики: тогда остаётся обрезка истории, попробуем на следующем ходу.
        if (summary.isBlank()) return false
        dao.setSummary(conversationId, summary, chunk.last().id)
        return true
    }

    /** Разбор реплики ученика. Возвращает сохранённый результат ("" — без ошибок). */
    suspend fun check(messageId: Long): String {
        val message = dao.message(messageId) ?: error("Сообщение удалено.")
        message.correction?.let { return it }
        val previous = dao.messages(message.conversationId).lastOrNull { it.id < messageId && !it.fromUser }?.text
        val raw = groq.complete(ConversationProtocol.correction(previous, message.text, message.voice), ConversationProtocol.CORRECTION_TOKENS)
        // Неразборчивый ответ модели не выдаём за ошибку ученика.
        val result = ConversationProtocol.parseCorrection(raw, message.text) ?: ""
        dao.setCorrection(messageId, result)
        return result
    }

    fun hint(conversationId: Long): Flow<String> = flow {
        val conversation = dao.get(conversationId) ?: error("Беседа удалена.")
        val prompt = ConversationProtocol.hint(Scenario.of(conversation.scenario), conversation.level,
            dao.messages(conversationId), conversation.summary)
        groq.stream(prompt, ConversationProtocol.HINT_TOKENS, temperature = 0.8f).collect { raw ->
            ConversationProtocol.cleanHint(raw).takeIf { it.isNotBlank() }?.let { emit(it) }
        }
    }

    suspend fun translate(messageId: Long): String {
        val message = dao.message(messageId) ?: error("Сообщение удалено.")
        message.translation?.let { return it }
        return translator.translate(message.text).also { dao.setTranslation(messageId, it) }
    }

    /** Убирает последний ответ собеседника, чтобы получить другой. */
    suspend fun dropLastReply(conversationId: Long) {
        val last = dao.messages(conversationId).lastOrNull() ?: return
        if (!last.fromUser && dao.messages(conversationId).any { it.fromUser }) dao.deleteFrom(conversationId, last.id)
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title.trim().take(60).ifEmpty { "Беседа" })
    suspend fun delete(id: Long) = dao.delete(id)
}
