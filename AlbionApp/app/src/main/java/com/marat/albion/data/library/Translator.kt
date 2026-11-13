package com.marat.albion.data.library

import com.marat.albion.data.ai.GroqClient
import com.marat.albion.data.translation.TranslationProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import java.util.Collections

/** EN → RU через Groq для реплик и примеров; словарь от ИИ не зависит. */
class Translator(private val groq: GroqClient) {
    val status = groq.status
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    })

    fun translations(text: String, context: String = ""): Flow<String> = flow {
        val prompt = TranslationProtocol.prompt(text, context)
        val key = prompt.joinToString("\n") { it.content }
        cache[key]?.let { emit(it); return@flow }
        var complete = ""
        groq.stream(prompt, maxTokens = 1_024, temperature = 0f).collect { raw ->
            complete = TranslationProtocol.clean(raw)
            if (complete.isNotBlank()) emit(complete)
        }
        check(complete.isNotBlank()) { "Модель вернула пустой перевод. Попробуйте другой фрагмент." }
        cache[key] = complete
    }
    suspend fun translate(text: String, context: String = ""): String = translations(text, context).last()
}
