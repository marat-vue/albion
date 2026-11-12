package com.marat.albion.data.games

import com.marat.albion.data.db.CardEntity

/** Пример карточки с пропуском на месте изучаемого слова. [answer] — форма слова в этом предложении. */
data class ClozeTask(val sentence: String, val ranges: List<IntRange>, val answer: String) {
    /** Предложение, где каждое вхождение слова заменено пропуском. */
    val masked: String get() = buildString {
        var pos = 0
        ranges.forEach { r -> append(sentence, pos, r.first); append(GAP); pos = r.last + 1 }
        append(sentence, pos, sentence.length)
    }

    companion object {
        const val GAP = "_____"
    }
}

/**
 * Cloze по примерам карточки. Слово ищется целиком или с окончанием (looks, stopped, studies,
 * giving up); неправильные формы (went) не угадываем — тогда пример не подходит.
 */
object Cloze {
    private const val SUFFIX = "(?:s|es|d|ed|ing|er|est)"

    fun make(card: CardEntity, preferSecond: Boolean = false): ClozeTask? {
        val examples = listOf(card.example1, card.example2).let { if (preferSecond) it.reversed() else it }
        val term = card.term.trim()
        return examples.firstNotNullOfOrNull { make(it, term) }
    }

    fun make(sentence: String, term: String): ClozeTask? {
        val text = sentence.trim()
        if (text.isEmpty() || term.isBlank()) return null
        // Плейсхолдеры (someone, one's) в предложении стоят конкретными словами — такие термины пропускаем.
        if (term.split(' ').any { AnswerCheck.isPlaceholder(it) }) return null
        val matches = pattern(term).findAll(text).map { it.range }.toList()
        if (matches.isEmpty()) return null
        val answer = text.substring(matches.first())
        // Пропуск не должен оставлять предложение пустым.
        if (text.length - matches.sumOf { it.last - it.first + 1 } < 8) return null
        return ClozeTask(text, matches, answer)
    }

    /** Первое слово выражения может стоять с окончанием, остальные — как в термине. */
    private fun pattern(term: String): Regex {
        val words = term.trim().split(Regex("\\s+"))
        val head = inflected(words.first())
        val tail = words.drop(1).joinToString("") { "\\s+" + Regex.escape(it) }
        return Regex("(?<![\\p{L}'])$head$tail(?![\\p{L}])", RegexOption.IGNORE_CASE)
    }

    private fun inflected(word: String): String {
        val w = Regex.escape(word)
        if (word.length < 3 || !word.all { it.isLetter() }) return w
        val variants = mutableListOf("$w$SUFFIX?")
        val last = word.last().lowercaseChar()
        if (last == 'e') variants += Regex.escape(word.dropLast(1)) + "(?:ed|ing|er|est)"
        if (last == 'y') variants += Regex.escape(word.dropLast(1)) + "(?:ies|ied|ier|iest)"
        // stop → stopped, big → bigger: удвоенная согласная.
        if (last !in "aeiouwxy") variants += "$w${Regex.escape(last.toString())}(?:ed|ing|er|est)"
        return variants.joinToString("|", "(?:", ")")
    }

    /**
     * Проверка ответа: форма из предложения (с допуском опечатки) — как обычно. Верное слово
     * в другой форме (look вместо looked) — «почти»: ошибка только в окончании.
     */
    fun check(input: String, task: ClozeTask, term: String): Verdict {
        val direct = AnswerCheck.check(input, listOf(task.answer))
        if (direct != Verdict.Wrong) return direct
        return if (AnswerCheck.checkTerm(input, term) != Verdict.Wrong) Verdict.Typo else Verdict.Wrong
    }
}
