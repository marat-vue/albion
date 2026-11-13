package com.marat.albion.data.conversation

import com.marat.albion.data.ai.ChatMessage
import com.marat.albion.data.db.ConversationMessageEntity

/** Ролевая ситуация: кого играет модель и с какой реплики начинается беседа. */
enum class Scenario(
    val id: String,
    val title: String,
    val tagline: String,
    /** Роль для системной подсказки — по-английски, так маленькая модель держит её надёжнее. */
    val role: String,
    val opener: String,
) {
    FREE("free", "Свободная беседа", "О чём угодно — темы предлагаете вы",
        "a friendly, curious conversation partner. Talk about anything the learner brings up",
        "Hi! It's nice to meet you. What would you like to talk about today?"),
    DAY("day", "Как прошёл день", "Расскажите о делах, планах и настроении",
        "a friend catching up with the learner about their day, plans and feelings",
        "Hey! How has your day been so far?"),
    CAFE("cafe", "В кафе", "Закажите кофе и что-нибудь к нему",
        "a barista in a busy London coffee shop. The learner is a customer ordering",
        "Hi there, welcome in! What can I get for you today?"),
    HOTEL("hotel", "В отеле", "Заселение, вопросы о номере и городе",
        "a receptionist at a hotel in Edinburgh. The learner is a guest checking in",
        "Good evening and welcome to the Riverside Hotel. Do you have a reservation with us?"),
    INTERVIEW("interview", "Собеседование", "Расскажите о себе и своём опыте",
        "a friendly interviewer at an international company. Ask the learner typical job interview questions one at a time",
        "Thanks for coming in today. Could you start by telling me a little about yourself?"),
    DOCTOR("doctor", "У врача", "Опишите самочувствие и симптомы",
        "a family doctor. The learner is a patient describing how they feel. Never give real medical advice; keep it a simple role-play",
        "Hello, please have a seat. What seems to be the problem today?"),
    SHOP("shop", "В магазине", "Найдите вещь, размер, спросите о цене",
        "a shop assistant in a clothing store. The learner is a customer",
        "Hi! Are you looking for anything in particular today?"),
    OPINION("opinion", "Обмен мнениями", "Спорьте, соглашайтесь, аргументируйте",
        "a thoughtful discussion partner. Share your own opinion briefly and ask for the learner's view and reasons",
        "Here's a question for you: is it better to work from home or from an office? What do you think?"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: FREE
    }
}

val CONVERSATION_LEVELS = listOf("A2", "B1", "B2", "C1")

/**
 * Подсказки для разговорной практики через Groq. Когда реплик становится много, самые старые
 * пересказываются ([compactionSize]): пересказ стоит в системной подсказке. Бюджет держит запрос
 * в пределах минутного лимита токенов бесплатного тарифа; обрезка — страховка, если сжать не удалось.
 */
object ConversationProtocol {
    const val REPLY_TOKENS = 300
    private const val PROMPT_BUDGET = 5_000
    /** История обрезается кратно стольким сообщениям. */
    private const val TRIM_STEP = 6
    /** История длиннее — пора сжимать. Запас до [PROMPT_BUDGET] — на пересказ и случай, когда сжатие не удалось. */
    private const val COMPACT_AT = 3_500
    /** Свежие реплики, которые остаются дословно: не меньше [MIN_KEEP] сообщений и примерно столько токенов. */
    private const val KEEP_TOKENS = 1_800
    private const val MIN_KEEP = 6
    /** Сколько истории пересказывается за раз. */
    private const val CHUNK_TOKENS = 1_500
    const val SUMMARY_TOKENS = 400
    const val CORRECTION_TOKENS = 400
    const val HINT_TOKENS = 100
    private fun data(value: String) = value.replace("\u0000", "").trim()

    /** Грубая оценка токенов для английского текста: ≈ 3,3 символа на токен. */
    fun estimateTokens(text: String) = text.length * 10 / 33 + 4
    fun estimateTokens(messages: List<ChatMessage>) = messages.sumOf { estimateTokens(it.content) + 4 }

    private fun levelRule(level: String) = when (level) {
        "A2" -> "The learner is a beginner (CEFR A2): use only very common words and short simple sentences. Reply in one or two short sentences."
        "B1" -> "The learner is intermediate (CEFR B1): use everyday vocabulary and clear sentences. Reply in two or three sentences."
        "B2" -> "The learner is upper-intermediate (CEFR B2): speak naturally, with some common idioms. Reply in two to four sentences."
        else -> "The learner is advanced (CEFR C1): speak like a native speaker, with rich vocabulary and idioms. Reply in two to four sentences."
    }

    private fun cost(message: ConversationMessageEntity) = estimateTokens(message.text) + 8

    fun system(scenario: Scenario, level: String, summary: String = "") =
        "You are ${scenario.role}. You are having a spoken conversation with someone who is practising English.\n" +
            "${levelRule(level)}\n" +
            "Rules:\n" +
            "- Always answer in British English, in a warm, natural spoken style, staying in your role.\n" +
            "- Remember what the learner has told you earlier and refer back to it naturally.\n" +
            "- React to what the learner said, then keep the conversation going with one question.\n" +
            "- Do not correct mistakes or explain grammar: a separate teacher does that. Just continue the conversation.\n" +
            "- If the learner writes in Russian or seems stuck, ask a simpler question in English.\n" +
            "- No emojis, no lists, no markdown, no stage directions." +
            (if (summary.isBlank()) "" else "\nEarlier in this conversation (you remember it; do not repeat it): ${data(summary)}")

    private fun turn(message: ConversationMessageEntity) =
        if (message.fromUser) ChatMessage.user(data(message.text)) else ChatMessage.assistant(data(message.text))

    /**
     * Запрос для следующей реплики собеседника. [history] — реплики после пересказа [summary],
     * заканчивается сообщением ученика.
     */
    fun reply(scenario: Scenario, level: String, history: List<ConversationMessageEntity>, summary: String = ""): List<ChatMessage> {
        require(history.isNotEmpty()) { "Напишите первую реплику." }
        val system = ChatMessage.system(system(scenario, level, summary))
        val budget = PROMPT_BUDGET - estimateTokens(system.content)
        var start = 0
        while (start < history.size - 2 && history.drop(start).sumOf(::cost) > budget) start += TRIM_STEP
        start = start.coerceAtMost(history.size - 1)
        return listOf(system) + history.drop(start).map(::turn)
    }

    /**
     * Сколько самых старых сообщений [history] (ещё не пересказанных) пора пересказать; 0 — рано.
     * Свежие реплики остаются дословно, за один раз пересказывается не больше [CHUNK_TOKENS].
     */
    fun compactionSize(history: List<ConversationMessageEntity>): Int {
        if (history.sumOf(::cost) <= COMPACT_AT) return 0
        var keep = 0
        var kept = 0
        for (message in history.asReversed()) {
            if (keep >= MIN_KEEP && kept + cost(message) > KEEP_TOKENS) break
            kept += cost(message)
            keep++
        }
        var take = 0
        var tokens = 0
        for (message in history.take(history.size - keep)) {
            if (take > 0 && tokens + cost(message) > CHUNK_TOKENS) break
            tokens += cost(message)
            take++
        }
        return take
    }

    /** Новый пересказ = прежний пересказ + [chunk]. */
    fun summary(scenario: Scenario, previous: String, chunk: List<ConversationMessageEntity>): List<ChatMessage> {
        require(chunk.isNotEmpty())
        val system = "You keep notes on a role-play conversation between an English learner and ${scenario.role}. " +
            "Rewrite the notes so they also cover the new messages. Write in English, in third person, at most 10 short sentences; " +
            "merge repetitive small talk into one sentence. " +
            "Always keep every name exactly as written (people, pets, places, companies) and what it refers to. " +
            "Also keep facts that matter later: numbers, what the learner said about themselves (job, family, hobbies, plans), " +
            "decisions, orders or agreements, topics already discussed, and open questions. " +
            "Do not mention grammar mistakes. Answer with the notes only."
        val notes = data(previous).ifBlank { "(none yet)" }
        val lines = chunk.joinToString("\n") { (if (it.fromUser) "Learner: " else "Partner: ") + data(it.text).take(800) }
        return listOf(ChatMessage.system(system), ChatMessage.user("Notes so far: $notes\n\nNew messages:\n$lines"))
    }

    fun cleanSummary(raw: String) = cleanReply(raw).removePrefix("Notes:").replace(Regex("\\s+"), " ").trim().take(1_500)

    /** Ответ модели: без рассуждений, ролей и разметки. */
    fun cleanReply(raw: String): String {
        var text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        if ("<think>" in text) text = text.substringBefore("<think>")
        // Модель иногда дописывает реплику за ученика.
        text = text.split(Regex("\\n\\s*(User|Learner|Student|You)\\s*:", RegexOption.IGNORE_CASE)).first()
        return text.replace(Regex("^\\s*(Assistant|AI|Barista|Doctor|Receptionist|Interviewer)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[*_#`]+"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Разбор фразы ученика с учётом реплики, на которую он отвечал. Отдельная задача, температура 0. */
    fun correction(previous: String?, sentence: String, voice: Boolean): List<ChatMessage> {
        val text = data(sentence).take(800)
        require(text.isNotBlank())
        val system = "You are a kind, precise English teacher for Russian speakers. Check the learner's message for grammar, " +
            "word choice, word order and unnatural phrasing (what a British native speaker would not say). " +
            "Ignore style, punctuation and capital letters" +
            (if (voice) " (the message was dictated, so ignore spelling)" else "") + ". " +
            "Use the partner's line only to understand the context. " +
            "If the message is correct and natural enough, answer exactly: OK\n" +
            "Otherwise answer in two lines:\nCorrect: <the whole message, corrected with minimal changes>\n" +
            "Why: <an explanation in Russian, one or two sentences: name each mistake and the rule>"
        val context = previous?.let { "Conversation partner said: ${data(it).take(400)}\n" }.orEmpty()
        return listOf(
            ChatMessage.system(system),
            ChatMessage.user("Learner wrote: I am agree with you."),
            ChatMessage.assistant("Correct: I agree with you.\nWhy: Agree — глагол, поэтому «am» не нужен: I agree, а не I am agree."),
            ChatMessage.user("Learner wrote: I went to the park yesterday with my sister."),
            ChatMessage.assistant("OK"),
            ChatMessage.user("${context}Learner wrote: $text"),
        )
    }

    /** "" — ошибок нет, иначе «исправление\nпояснение». null — ответ модели не удалось разобрать. */
    fun parseCorrection(raw: String, original: String): String? {
        val text = cleanReply(raw)
        if (text.isBlank()) return null
        if (Regex("^OK\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && !text.contains("Correct:", true)) return ""
        val corrected = Regex("Correct\\s*:\\s*(.+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() } ?: return null
        val why = Regex("(?s)Why\\s*:\\s*(.+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (normalize(corrected) == normalize(original)) "" else "$corrected\n$why".trim()
    }

    /** Совпадение без учёта регистра и пунктуации — тогда исправлять нечего. */
    fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()

    /** Вариант ответа, если ученик не знает, что сказать. */
    fun hint(scenario: Scenario, level: String, history: List<ConversationMessageEntity>, summary: String = ""): List<ChatMessage> {
        val recent = history.takeLast(4).joinToString("\n") {
            (if (it.fromUser) "Learner: " else "Partner: ") + data(it.text).take(400)
        }
        val system = "You help an English learner (CEFR $level) in a role-play where the partner is ${scenario.role}. " +
            "Suggest one short, natural thing the learner could say next, in British English. Answer with that reply only, " +
            "no quotes, no explanations." + (if (summary.isBlank()) "" else " Earlier in the conversation: ${data(summary)}")
        return listOf(ChatMessage.system(system), ChatMessage.user("$recent\n\nWhat could the learner say next?"))
    }

    fun cleanHint(raw: String) = cleanReply(raw).removePrefix("Learner:").trim().removeSurrounding("\"").lines().first().trim()

    fun errorMessage(error: Throwable): String = when {
        error is IllegalArgumentException -> error.message ?: "Не удалось подготовить сообщение."
        else -> error.message?.takeIf { it.any { c -> c in 'А'..'я' } }
            ?: "Модель не ответила. Проверьте VPN и попробуйте ещё раз."
    }
}
