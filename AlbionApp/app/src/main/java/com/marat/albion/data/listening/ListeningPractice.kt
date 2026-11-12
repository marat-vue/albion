package com.marat.albion.data.listening

import com.marat.albion.data.games.AnswerCheck
import com.marat.albion.data.library.WordMarker
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Практика по треку. */
@Serializable
enum class ListeningPracticeMode(val title: String, val hint: String) {
    DICTATION("Диктант", "Послушайте фразу и запишите её"),
    CLOZE("Пропуски", "Слова из ваших коллекций в живой речи"),
    SHADOWING("Шэдоуинг", "Повторяйте за чтецом фразу за фразой"),
}

/**
 * Итог диктанта по фразе: [mask] — символы [expected], написанные неверно или пропущенные;
 * [missedWords] — слова, пропущенные целиком (чаще всего a/the, have, окончания на слух).
 */
data class DictationResult(val expected: String, val mask: BooleanArray, val accuracy: Float, val missedWords: List<String> = emptyList()) {
    /** Мелкие опечатки допустимы, пропущенное слово — даже короткий артикль — уже нет. */
    val passed: Boolean get() = accuracy >= PASS && missedWords.isEmpty()

    override fun equals(other: Any?) = other is DictationResult && expected == other.expected &&
        accuracy == other.accuracy && mask.contentEquals(other.mask) && missedWords == other.missedWords
    override fun hashCode() = 31 * expected.hashCode() + mask.contentHashCode()

    companion object { const val PASS = 0.9f }
}

/**
 * Диктант: сравнение по буквам (как в «Письме», [AnswerCheck.mistakeMask]) без учёта регистра,
 * пунктуации и типографских кавычек — на слух их не различить.
 */
object Dictation {
    fun check(answer: String, segmentText: String): DictationResult {
        val expected = forDictation(segmentText)
        val typed = forDictation(answer)
        val mask = AnswerCheck.mistakeMask(typed, expected)
        val letters = expected.count { it != ' ' }
        val wrong = mask.indices.count { mask[it] && expected[it] != ' ' }
        // Лишние буквы в ответе тоже ошибка: иначе «напечатать всё подряд» давало бы 100%.
        // Замена буквы — и пропуск, и лишняя, поэтому берём большее, а не сумму.
        val extra = (typed.count { it != ' ' } - (letters - wrong)).coerceAtLeast(0)
        val penalty = maxOf(wrong, extra)
        val accuracy = if (letters == 0) 1f else (1f - penalty.toFloat() / letters).coerceIn(0f, 1f)
        // Слово «пропущено», если неверна больше чем половина его букв.
        val missed = Regex("\\S+").findAll(expected).filter { m -> m.range.count { mask[it] } * 2 > m.value.length }
            .map { it.value }.toList()
        return DictationResult(expected, mask, accuracy, missed)
    }

    /** Текст фразы так, как его можно услышать: без кавычек, тире и знаков препинания. */
    fun forDictation(text: String): String = text
        .replace('’', '\'').replace('‘', '\'')
        .replace(Regex("[\"“”«»—–()\\[\\]…]"), " ")
        .replace(Regex("(?<![A-Za-z])'|'(?![A-Za-z])"), " ")
        .replace(Regex("[.,!?;:]"), " ")
        .replace(Regex("-(?![A-Za-z])|(?<![A-Za-z])-"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Пропуск в фразе: [range] — символы ответа в [Segment.text]. */
data class ClozeItem(val segment: Int, val text: String, val range: IntRange) {
    val answer: String get() = text.substring(range)
    val before: String get() = text.substring(0, range.first)
    val after: String get() = text.substring(range.last + 1)
}

/**
 * Cloze по транскрипту: пропуски — только на словах и выражениях из ваших коллекций
 * (с учётом форм: studied → study), не больше одного на фразу — чтобы контекст оставался понятным.
 */
object ListeningCloze {
    fun build(transcript: Transcript, marker: WordMarker, limit: Int = 10, random: Random = Random.Default): List<ClozeItem> {
        if (marker.isEmpty) return emptyList()
        val candidates = transcript.segments.mapIndexedNotNull { i, seg ->
            val ranges = marker.ranges(seg.text)
            if (ranges.isEmpty()) null else ClozeItem(i, seg.text, ranges[random.nextInt(ranges.size)])
        }
        return candidates.shuffled(random).take(limit).sortedBy { it.segment }
    }

    /** Ответ сверяется со словом в той форме, что звучит в записи; опечатка — «почти». */
    fun check(answer: String, item: ClozeItem) = AnswerCheck.check(answer, listOf(item.answer))
}
