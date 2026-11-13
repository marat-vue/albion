package com.marat.albion.data.repo

import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.LeechHintEntity
import com.marat.albion.data.ai.ChatMessage
import com.marat.albion.data.ai.GroqClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Мнемоника и контрастные примеры к трудному слову. [edited] — текст поправлен учеником. */
data class LeechHint(val mnemonic: String, val examples: List<String>, val edited: Boolean = false)

/** Где лежат подсказки: Room в приложении, память в тестах. */
interface LeechHintStore {
    suspend fun get(cardId: String): LeechHintEntity?
    fun observe(cardId: String): Flow<LeechHintEntity?>
    suspend fun upsert(row: LeechHintEntity)
    suspend fun delete(cardId: String)
}

/**
 * Подсказки к трудным словам: модель пишет черновик только по просьбе ученика, результат кэшируется
 * по id карточки. Ручная правка не перезаписывается — ни повторной генерацией, ни кэшем.
 */
class LeechHints(
    private val store: LeechHintStore,
    /** Запрос → сырой ответ модели. В приложении — Groq. */
    private val generate: suspend (prompt: List<ChatMessage>) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    fun observe(cardId: String): Flow<LeechHint?> = store.observe(cardId).map { it?.toHint() }

    /** Сохранённая подсказка или новая от модели. Повторное нажатие во время генерации модель не запускает. */
    suspend fun hint(card: CardEntity): LeechHint = mutex.withLock {
        store.get(card.id)?.toHint() ?: create(card)
    }

    /** Другой вариант от модели. Поправленный вручную текст остаётся как есть. */
    suspend fun regenerate(card: CardEntity): LeechHint = mutex.withLock {
        val saved = store.get(card.id)
        if (saved?.edited == true) saved.toHint() else create(card)
    }

    /** Ручная правка. Пустые мнемоника и примеры — удалить подсказку. */
    suspend fun edit(cardId: String, mnemonic: String, examples: String) = mutex.withLock {
        val text = mnemonic.trim()
        val lines = LeechHintProtocol.lines(examples)
        if (text.isEmpty() && lines.isEmpty()) store.delete(cardId)
        else store.upsert(LeechHintEntity(cardId, text, lines.joinToString("\n"), edited = true, updatedAt = clock()))
    }

    private suspend fun create(card: CardEntity): LeechHint {
        val hint = LeechHintProtocol.parse(generate(LeechHintProtocol.prompt(card)), card.term)
            ?: error("Модель не справилась с подсказкой. Попробуйте ещё раз или напишите свою.")
        store.upsert(LeechHintEntity(card.id, hint.mnemonic, hint.examples.joinToString("\n"), edited = false, updatedAt = clock()))
        return hint
    }

    companion object {
        fun groq(store: LeechHintStore, groq: GroqClient) = LeechHints(store, generate = { prompt ->
            groq.complete(prompt, LeechHintProtocol.MAX_TOKENS, temperature = 0.7f)
        })
    }
}

private fun LeechHintEntity.toHint() = LeechHint(mnemonic, LeechHintProtocol.lines(examples), edited)

/** Подсказка для модели и разбор её ответа по меткам. */
object LeechHintProtocol {
    const val MAX_TOKENS = 300
    private val LABEL = Regex("^\\s*[-*]?\\s*(KEY|КЛЮЧ|IMAGE|ОБРАЗ|EN\\s*\\d)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)

    private fun data(value: String, limit: Int) = value.replace(Regex("\\s+"), " ").trim().take(limit)

    fun prompt(card: CardEntity): List<ChatMessage> {
        val system = "You help Russian speakers remember English words they keep forgetting. " +
            "Use the given meaning as is, do not change it. Answer with exactly these lines:\n" +
            "KEY: a Russian word or short phrase that sounds like the English word\n" +
            "IMAGE: one vivid sentence in Russian (max 20 words) linking KEY to the meaning\n" +
            "EN1: a short British English example (max 10 words, simple words) with the word\n" +
            "EN2: an example contrasting it with a word it is often confused with, or another meaning\n" +
            "EN3: one more short example with the word\n" +
            "No transcription in Cyrillic, no invented etymology, no other text."
        fun task(term: String, ipa: String, pos: String, meaning: String) =
            "Word: $term\nIPA: $ipa\nPart of speech: $pos\nMeaning: $meaning"
        return listOf(
            ChatMessage.system(system),
            ChatMessage.user(task("ship", "/ʃɪp/", "noun", "корабль, судно")),
            ChatMessage.assistant("KEY: шип\nIMAGE: Огромный корабль напоролся на шип скалы и дал течь.\n" +
                "EN1: The ship left the harbour at dawn.\nEN2: A ship carries cargo, but sheep eat grass.\nEN3: We travelled to France by ship."),
            ChatMessage.user(task(data(card.term, 80), data(card.ipa, 60), data(card.pos, 30), data(card.meaningRu, 200))),
        )
    }

    /** Ответ модели → подсказка; null, если меток нет или ни один пример не содержит слово. */
    fun parse(raw: String, term: String): LeechHint? {
        var text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        if ("<think>" in text) text = text.substringBefore("<think>")
        text = text.replace(Regex("[*_#`]+"), "")
        var key = ""
        var image = ""
        val examples = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            val match = LABEL.find(line) ?: return@forEach
            val value = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("«", "»").trim()
            if (value.isEmpty()) return@forEach
            when (match.groupValues[1].uppercase().first()) {
                'K', 'К' -> key = value
                'I', 'О' -> image = value
                else -> if (examples.size < 3) examples += value.take(160)
            }
        }
        if (image.isEmpty() || examples.none { mentions(it, term) }) return null
        val mnemonic = if (key.isEmpty() || key.equals(term, ignoreCase = true)) image else "«$key» — $image"
        return LeechHint(mnemonic.take(300), examples.distinct())
    }

    /** Пример содержит каждое слово термина, хотя бы по основе: look → looked, study → studies. */
    fun mentions(example: String, term: String): Boolean {
        val words = term.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val text = example.lowercase()
        return words.all { word ->
            val stem = if (word.length > 4) word.dropLast(1) else word
            Regex("(?<![\\p{L}])" + Regex.escape(stem)).containsMatchIn(text)
        }
    }

    /** Строки примеров без пустых и маркеров списка. */
    fun lines(examples: String): List<String> =
        examples.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
}
