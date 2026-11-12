package com.marat.albion.data.repo

import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.AnswerCheck

/**
 * Похожие слова (affect / effect, through / though, «большой» у big и large) рядом в очереди
 * мешают друг другу. Постпроход после перемешивания разводит их на несколько карточек.
 */
object SimilarWords {
    /** Зазор между похожими словами в длинной очереди — объём рабочей памяти. */
    const val GAP = 5
    /** В короткой очереди хватает и такого. */
    const val SHORT_GAP = 3
    /** Карточка сдвигается вперёд не дальше стольких позиций — порядок по сроку почти не страдает. */
    const val MAX_SHIFT = 10

    private val IPA_MARKS = Regex("[/\\[\\]ˈˌː.\\s‿]")
    private val MEANING_SPLIT = Regex("[;,/]")
    private val BRACKETS = Regex("\\([^)]*\\)")

    private class Keys(val term: String, val ipa: String, val meanings: Set<String>)

    private fun keys(card: CardEntity) = Keys(
        term = AnswerCheck.normalize(card.term),
        // У слова из нескольких значений IPA склеена через « · » — сравниваем первую.
        ipa = card.ipa.substringBefore('·').replace(IPA_MARKS, "").lowercase(),
        meanings = card.meaningRu.replace(BRACKETS, " ").split(MEANING_SPLIT)
            .map { it.trim().lowercase() }.filter { it.length >= 3 }.toSet(),
    )

    fun similar(a: CardEntity, b: CardEntity): Boolean = similar(keys(a), keys(b))

    private fun similar(a: Keys, b: Keys): Boolean {
        if (a.term == b.term) return true
        if (close(a.term, b.term)) return true
        if (a.ipa.length >= 3 && b.ipa.length >= 3 && AnswerCheck.levenshtein(a.ipa, b.ipa) <= 1) return true
        return a.meanings.any { it in b.meanings }
    }

    /** Одна-две буквы разницы при достаточной длине: cat / cut, affect / effect, quite / quiet. */
    private fun close(a: String, b: String): Boolean {
        val len = minOf(a.length, b.length)
        if (len < 3) return false
        val allowed = if (len >= 6) 2 else 1
        if (kotlin.math.abs(a.length - b.length) > allowed) return false
        return transposeDistance(a, b) <= allowed
    }

    /** Левенштейн, где перестановка соседних букв (quite / quiet) — одна правка. */
    internal fun transposeDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /**
     * Жадно строит очередь: на каждое место берёт первую карточку (не дальше [MAX_SHIFT]), не похожую
     * на предыдущие [GAP]; если такой нет — первую по порядку.
     */
    fun spread(cards: List<CardEntity>, gap: Int = if (cards.size >= 4 * GAP) GAP else SHORT_GAP): List<CardEntity> {
        if (cards.size < 2) return cards
        val rest = cards.mapTo(ArrayList(cards.size)) { it to keys(it) }
        val result = ArrayList<Pair<CardEntity, Keys>>(cards.size)
        while (rest.isNotEmpty()) {
            val recent = result.takeLast(gap)
            val limit = minOf(rest.size, MAX_SHIFT + 1)
            val pick = (0 until limit).firstOrNull { i ->
                recent.none { similar(it.second, rest[i].second) }
            } ?: 0
            result += rest.removeAt(pick)
        }
        return result.map { it.first }
    }
}
