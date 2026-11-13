package com.marat.albion.data.ai

import kotlinx.serialization.json.*

/** Одна реплика запроса к модели: роли разделяет API, текст пользователя не может стать инструкцией. */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

/** Как модель рассуждает и какие параметры для этого нужны. */
enum class Reasoning {
    /** GPT-OSS: рассуждения не отключаются — коротко и скрыто, но они входят в лимит ответа. */
    HIDDEN,
    /** Qwen на Groq: рассуждения можно выключить совсем. */
    SWITCHABLE,
    NONE,
}

/**
 * Модели бесплатного тарифа Groq, от самой сильной к самой слабой: [ModelLadder] берёт первую,
 * у которой не исчерпан лимит. У каждой модели свой лимит. [context] — окно модели в токенах.
 * [strong] — модели, которым доверяем долговременное: пересказ беседы остаётся в ней навсегда.
 */
enum class GroqModel(val id: String, val title: String, val reasoning: Reasoning, val context: Int = 131_072,
                     val strong: Boolean = true) {
    GPT_OSS_120B("openai/gpt-oss-120b", "GPT-OSS 120B", Reasoning.HIDDEN),
    QWEN_27B("qwen/qwen3.8-27b", "Qwen3.8 27B", Reasoning.SWITCHABLE),
    GPT_OSS_20B("openai/gpt-oss-20b", "GPT-OSS 20B", Reasoning.HIDDEN),
    /** Арабско-английская 7B с окном 4K: последний запас, объяснения по-русски у неё слабые. */
    ALLAM_7B("allam-2-7b", "ALLaM 2 7B", Reasoning.NONE, context = 4_096, strong = false),
    ;

    companion object {
        val STRONGEST = entries.first()
    }
}

/** Ошибка запроса к ИИ с понятным ученику текстом. */
open class AiException(message: String) : Exception(message)

/** Лимит модели исчерпан; снова пробовать через [waitMs]. */
class RateLimitException(message: String, val waitMs: Long) : AiException(message)

/** Сильные модели на паузе, а задача не доверяется слабой. */
class StrongModelsBusyException : AiException("Сильные модели Groq отдыхают после лимита. Попробуйте позже.")

/** Модель недоступна этому аккаунту или снята с Groq. */
class ModelUnavailableException(message: String) : AiException(message)

/** Лестница моделей: самая сильная из тех, что сейчас не отдыхают после исчерпанного лимита. */
object ModelLadder {
    /** null — лимиты всех моделей исчерпаны. [restUntil] — id модели → до какого момента (мс) её не трогать. */
    fun pick(restUntil: Map<String, Long>, now: Long, strongOnly: Boolean = false): GroqModel? =
        GroqModel.entries.firstOrNull { (!strongOnly || it.strong) && (restUntil[it.id] ?: 0L) <= now }

    /** Когда освободится хоть какая-то модель. */
    fun nextFree(restUntil: Map<String, Long>, now: Long): Long =
        GroqModel.entries.minOf { (restUntil[it.id] ?: 0L).coerceAtLeast(now) }
}

/** OpenAI-совместимый формат Groq: тело запроса, поток SSE и ошибки. Без сети — проверяется JVM-тестами. */
object GroqProtocol {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    /** Скрытые рассуждения входят в лимит ответа: запас, чтобы ответ не обрезался на полуслове. */
    const val REASONING_ROOM = 1_024
    /** Пауза, если Groq не сказал, сколько ждать. */
    const val DEFAULT_WAIT_MS = 60_000L

    /** Грубая оценка токенов: ≈ 3 символа на токен с запасом для русского текста. */
    private fun tokens(message: ChatMessage) = message.content.length / 3 + 8

    /**
     * Для модели с маленьким окном выбрасывает самые старые реплики беседы, оставляя системную подсказку
     * и последнюю реплику. У больших моделей запрос не меняется.
     */
    fun fit(model: GroqModel, messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        val budget = model.context * 3 / 4 - maxTokens
        val result = messages.toMutableList()
        while (result.size > 2 && result.sumOf(::tokens) > budget) {
            val oldest = result.indexOfFirst { it.role != "system" }
            if (oldest < 0 || oldest == result.lastIndex) break
            result.removeAt(oldest)
        }
        return result
    }

    fun request(model: GroqModel, messages: List<ChatMessage>, maxTokens: Int, temperature: Float, stream: Boolean): String {
        val limit = if (model.reasoning == Reasoning.HIDDEN) maxTokens + REASONING_ROOM else maxTokens
        return buildJsonObject {
            put("model", model.id)
            putJsonArray("messages") {
                fit(model, messages, limit).forEach {
                    addJsonObject { put("role", it.role); put("content", it.content.replace("\u0000", "")) }
                }
            }
            put("max_completion_tokens", limit)
            put("temperature", temperature)
            put("stream", stream)
            when (model.reasoning) {
                Reasoning.HIDDEN -> { put("reasoning_effort", "low"); put("include_reasoning", false) }
                Reasoning.SWITCHABLE -> put("reasoning_effort", "none")
                Reasoning.NONE -> Unit
            }
        }.toString()
    }

    /** Кусок текста из строки потока SSE; null — служебная строка, пустой ответ или конец потока. */
    fun delta(line: String): String? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        json["error"]?.let { throw AiException(apiMessage(it) ?: "Groq прервал ответ. Попробуйте ещё раз.") }
        return json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    /** Текст ответа без потока. Не JSON (например, страница VPN-провайдера) или пустой ответ — понятная ошибка. */
    fun content(body: String): String {
        val json = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
            ?: throw AiException("Groq вернул непонятный ответ. Проверьте VPN и повторите.")
        return json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AiException("Модель вернула пустой ответ. Попробуйте ещё раз.")
    }

    private fun apiMessage(error: JsonElement): String? =
        (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull

    /** «1h2m3.5s», «7m12.5s», «850ms» → миллисекунды; null — не распознано. */
    fun duration(text: String): Long? {
        val match = Regex("(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+(?:\\.\\d+)?)s)?(?:(\\d+(?:\\.\\d+)?)ms)?").find(text.trim())
            ?.takeIf { it.value.isNotEmpty() } ?: return null
        val (h, m, s, ms) = match.destructured
        return (h.toLongOrNull() ?: 0) * 3_600_000 + (m.toLongOrNull() ?: 0) * 60_000 +
            ((s.toDoubleOrNull() ?: 0.0) * 1000).toLong() + (ms.toDoubleOrNull() ?: 0.0).toLong()
    }

    /** Сколько ждать после 429: заголовок retry-after (секунды), иначе «try again in …» из текста ошибки. */
    fun waitMs(retryAfter: String?, detail: String): Long {
        retryAfter?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(1_000) }
        Regex("try again in ([\\d.hms]+)", RegexOption.IGNORE_CASE).find(detail)?.groupValues?.get(1)
            ?.let(::duration)?.let { return it.coerceAtLeast(1_000) }
        return DEFAULT_WAIT_MS
    }

    /** HTTP-ошибка → сообщение для ученика. [retryAfter] — заголовок ответа. */
    fun error(code: Int, body: String, retryAfter: String? = null): AiException {
        val detail = runCatching { apiMessage(Json.parseToJsonElement(body).jsonObject["error"] ?: JsonNull) }.getOrNull().orEmpty()
        val lower = detail.lowercase()
        return when {
            code == 429 -> RateLimitException("Лимит бесплатного тарифа Groq исчерпан. Подождите и повторите.", waitMs(retryAfter, detail))
            code == 404 || "model_not_found" in lower || "does not exist" in lower || "decommissioned" in lower ->
                ModelUnavailableException("Модель недоступна в Groq.")
            code == 401 -> AiException("Ключ Groq не подошёл. Проверьте его в настройках собеседника.")
            code == 403 -> AiException("Groq отказал в доступе. Включите VPN и повторите.")
            code == 413 || "context" in lower -> AiException("Запрос слишком длинный. Начните новую беседу или выделите меньше текста.")
            code in 500..599 -> AiException("Groq временно недоступен. Повторите чуть позже.")
            else -> AiException("Groq не ответил (код $code). Проверьте VPN и повторите.")
        }
    }
}
