package com.marat.albion.data.listening

import kotlinx.serialization.Serializable

/** Слово с временем звучания, мс от начала записи. */
@Serializable
data class TimedWord(val s: Long, val e: Long, val w: String)

/** Фраза транскрипта; [words] пуст, если разметка только по фразам (SRT/LRC). */
@Serializable
data class Segment(val s: Long, val e: Long, val text: String, val words: List<TimedWord> = emptyList())

/**
 * Транскрипт, синхронный с записью. Фразы упорядочены по началу и не пустые — это
 * гарантируют парсеры ([TranscriptParser]). Поиск текущей фразы и слова — бинарный,
 * поэтому опрос позиции раз в 50 мс не зависит от длины трека.
 */
class Transcript(val segments: List<Segment>) {
    private val starts = LongArray(segments.size) { segments[it].s }

    /** Есть ли пословная разметка — иначе подсвечивается фраза целиком. */
    val wordLevel: Boolean = segments.any { it.words.isNotEmpty() }

    val durationMs: Long = segments.maxOfOrNull { it.e } ?: 0L

    /** Символьные диапазоны слов в тексте фразы; считаются один раз на фразу. */
    private val ranges = arrayOfNulls<List<IntRange?>>(segments.size)

    /**
     * Фраза, которая звучит в [ms], или последняя прозвучавшая (в паузе между фразами
     * подсветка остаётся на ней). -1 — до начала первой фразы.
     */
    fun segmentAt(ms: Long): Int = lastStartAtOrBefore(starts, ms)

    /** Звучит ли сейчас сама фраза, а не пауза после неё. */
    fun isInside(index: Int, ms: Long): Boolean = segments.getOrNull(index)?.let { ms >= it.s && ms < it.e } == true

    /** Слово фразы [segment], звучащее в [ms]; в паузе между словами — предыдущее. -1 — нет. */
    fun wordAt(segment: Int, ms: Long): Int {
        val words = segments.getOrNull(segment)?.words ?: return -1
        if (words.isEmpty()) return -1
        var lo = 0
        var hi = words.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].s <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Где в тексте фразы стоит каждое слово разметки (null — не нашлось). */
    fun wordRanges(segment: Int): List<IntRange?> =
        ranges[segment] ?: locateWords(segments[segment].text, segments[segment].words.map { it.w }).also { ranges[segment] = it }

    /** Индекс слова под символом [offset] текста фразы; -1 — между словами. */
    fun wordAtOffset(segment: Int, offset: Int): Int = wordRanges(segment).indexOfFirst { it != null && offset in it }

    companion object {
        val EMPTY = Transcript(emptyList())
    }
}

/** Индекс последнего элемента ≤ [value] в отсортированном массиве; -1, если все больше. */
internal fun lastStartAtOrBefore(sorted: LongArray, value: Long): Int {
    var lo = 0
    var hi = sorted.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] <= value) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/**
 * Находит слова разметки в тексте по порядку: «Nuttel,» в тексте и «Nuttel» в разметке —
 * одно слово. Сравнение без регистра и с прямыми апострофами; ненайденное слово не сдвигает курсор.
 */
fun locateWords(text: String, words: List<String>): List<IntRange?> {
    val folded = fold(text)
    var cursor = 0
    return words.map { raw ->
        val w = fold(raw).trim { !it.isLetterOrDigit() }
        if (w.isEmpty()) return@map null
        // Слово должно начинаться и заканчиваться на границе: «a» не ищется внутри «Nuttel».
        var at = folded.indexOf(w, cursor)
        while (at >= 0 && !isBoundary(folded, at, at + w.length)) at = folded.indexOf(w, at + 1)
        if (at < 0) null else (at until at + w.length).also { cursor = at + w.length }
    }
}

private fun isBoundary(text: String, start: Int, end: Int): Boolean =
    (start == 0 || !text[start - 1].isLetterOrDigit()) && (end >= text.length || !text[end].isLetterOrDigit())

/** Регистр и типографские апострофы не важны; длина строки не меняется. */
private fun fold(s: String): String = buildString(s.length) {
    s.forEach { ch -> append(if (ch == '’' || ch == '‘') '\'' else ch.lowercaseChar()) }
}

private val TAP_WORD = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

/** Слово под символом [offset] (касание текста): «Nuttel,» → «Nuttel»; null — пробел или знак препинания. */
fun wordRangeAt(text: String, offset: Int): IntRange? =
    TAP_WORD.findAll(text).firstOrNull { offset in it.range }?.range
