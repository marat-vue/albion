package com.marat.albion.data.games

import java.util.Locale
import kotlin.math.max

/** Насколько ответ совпал с правильным. */
enum class Verdict { Correct, Typo, Wrong }

/**
 * Сравнение ответов в мини-играх: без учёта регистра, лишних пробелов,
 * типографских апострофов и финальной пунктуации. В словарных играх мелкая
 * опечатка засчитывается как «почти»; грамматические формы проверяются строго.
 */
object AnswerCheck {

    private val GRAMMAR_SPACES = Regex("[\\s\\p{Zs}]+")
    private val TERMINAL_PUNCTUATION = Regex("[.!?]+$")

    fun normalize(s: String): String = s
        .lowercase()
        .replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').replace('`', '\'')
        .replace(Regex("[.,!?;:\"«»()]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 — полное совпадение, 0.0 — ничего общего. */
    fun similarity(a: String, b: String): Float {
        val x = normalize(a)
        val y = normalize(b)
        val len = max(x.length, y.length)
        return if (len == 0) 1f else 1f - levenshtein(x, y).toFloat() / len
    }

    /** Сколько опечаток простительно для слова такой длины. */
    private fun tolerance(length: Int) = when {
        length >= 9 -> 2
        length >= 5 -> 1
        else -> 0
    }

    /** Проверка напечатанного ответа против одного или нескольких правильных вариантов. */
    fun check(answer: String, accepted: List<String>): Verdict {
        val a = normalize(answer)
        if (a.isEmpty()) return Verdict.Wrong
        val options = accepted.map(::normalize).filter { it.isNotEmpty() }
        if (options.any { it == a }) return Verdict.Correct
        val (dist, target) = options.map { levenshtein(a, it) to it }.minByOrNull { it.first } ?: return Verdict.Wrong
        return if (dist <= tolerance(target.length)) Verdict.Typo else Verdict.Wrong
    }

    /**
     * В грамматике близкая по написанию форма может менять время, согласование
     * или отрицание. Засчитываем только явно допустимый ответ, без допуска опечаток.
     * В заданиях на письмо [exactWriting] сохраняет регистр и конечную пунктуацию.
     * Запятые учитываются только при [commasMatter] (по умолчанию — в заданиях на письмо).
     */
    fun checkGrammar(
        answer: String,
        accepted: List<String>,
        exactWriting: Boolean = false,
        commasMatter: Boolean = exactWriting,
    ): Verdict {
        val actual = normalizeGrammar(answer, exactWriting, commasMatter)
        if (actual.isEmpty()) return Verdict.Wrong
        return if (accepted.any { normalizeGrammar(it, exactWriting, commasMatter) == actual }) Verdict.Correct else Verdict.Wrong
    }

    /**
     * Апострофы и прочая внутренняя пунктуация значимы: "its" и "it's" — разные ответы.
     * Пропущенная или лишняя запятая ошибкой не считается, если не задано [commasMatter].
     */
    fun normalizeGrammar(s: String, exactWriting: Boolean = false, commasMatter: Boolean = exactWriting): String {
        val apostrophes = s.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').replace('`', '\'')
        val text = (if (commasMatter) apostrophes else apostrophes.replace(",", ""))
            .replace(GRAMMAR_SPACES, " ").trim()
        return if (exactWriting) text else text.lowercase(Locale.ROOT).replace(TERMINAL_PUNCTUATION, "").trimEnd()
    }

    private val SOMEONE = setOf("someone", "somebody", "something", "sb", "smb", "sth", "smth")
    private val POSSESSIVE_SLOT = setOf("someone's", "somebody's", "one's", "sb's", "smb's")
    private val POSSESSIVES = setOf("my", "your", "his", "her", "its", "our", "their")

    /** Слово-подстановка (someone, something, one's…) — в подсказках его показываем открыто. */
    fun isPlaceholder(word: String): Boolean = normalize(word).let { it in SOMEONE || it in POSSESSIVE_SLOT }

    /**
     * Ответ на термин из карточки. Если в выражении есть подстановки
     * (drive **someone** up the wall, catch **someone's** eye), засчитываются и они сами,
     * и сокращения (sb, sth), и конкретные слова: «drive me up the wall», «catch my boss's eye».
     * someone / something — одно-три слова, someone's / one's — притяжательное слово.
     */
    fun checkTerm(answer: String, term: String): Verdict {
        val t = normalize(term).split(' ').filter { it.isNotEmpty() }
        if (t.none { it in SOMEONE || it in POSSESSIVE_SLOT }) return check(answer, listOf(term))
        val a = normalize(answer).split(' ').filter { it.isNotEmpty() }
        if (a.isEmpty()) return Verdict.Wrong
        var best = Int.MAX_VALUE
        var bestLength = 0
        for ((want, got) in alignments(t, 0, a, 0)) {
            if (want == got) return Verdict.Correct
            val d = levenshtein(want, got)
            if (d < best) { best = d; bestLength = want.length }
        }
        return if (best != Int.MAX_VALUE && best <= tolerance(bestLength)) Verdict.Typo else Verdict.Wrong
    }

    /**
     * Все способы сопоставить ответ с выражением: подстановка «съедает» 1–3 слова ответа
     * (притяжательная — одно слово с 's или my/his…). Возвращает пары «постоянная часть
     * выражения — соответствующая часть ответа», которые дальше сравниваются с допуском опечаток.
     */
    private fun alignments(t: List<String>, ti: Int, a: List<String>, ai: Int): List<Pair<String, String>> {
        if (ti == t.size) return if (ai == a.size) listOf("" to "") else emptyList()
        val token = t[ti]
        val result = mutableListOf<Pair<String, String>>()
        when (token) {
            in SOMEONE -> for (k in 1..3) {
                if (ai + k > a.size) break
                result += alignments(t, ti + 1, a, ai + k)
            }
            in POSSESSIVE_SLOT -> if (ai < a.size) {
                val w = a[ai]
                if (w in POSSESSIVES || w in POSSESSIVE_SLOT || w.endsWith("'s") || w.endsWith("s'")) result += alignments(t, ti + 1, a, ai + 1)
                // «my boss's»: притяжательное из двух слов.
                if (ai + 1 < a.size && a[ai + 1].endsWith("'s")) result += alignments(t, ti + 1, a, ai + 2)
            }
            else -> if (ai < a.size) {
                alignments(t, ti + 1, a, ai + 1).forEach { (w, g) ->
                    result += (token + " " + w).trim() to (a[ai] + " " + g).trim()
                }
            }
        }
        return result
    }

    /**
     * Форма неправильного глагола: можно ввести один из вариантов
     * («abided» или «abode») либо оба через «/» или запятую. Каждый вариант
     * должен быть правильной формой, опечатки не засчитываются.
     */
    fun checkForm(answer: String, variants: List<String>): Verdict {
        val parts = answer.split('/', ',')
        return if (parts.all { checkGrammar(it, variants) == Verdict.Correct }) Verdict.Correct else Verdict.Wrong
    }

    /**
     * Маска символов правильного ответа, которые пользователь написал неверно
     * (true — символ пропущен или искажён). Строится по наибольшей общей подпоследовательности.
     */
    fun mistakeMask(answer: String, correct: String): BooleanArray {
        val a = answer.lowercase()
        val c = correct.lowercase()
        val dp = Array(c.length + 1) { IntArray(a.length + 1) }
        for (i in c.indices.reversed()) for (j in a.indices.reversed()) {
            dp[i][j] = if (c[i] == a[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
        }
        val mask = BooleanArray(c.length) { true }
        var i = 0
        var j = 0
        while (i < c.length && j < a.length) {
            when {
                c[i] == a[j] -> { mask[i] = false; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        correct.forEachIndexed { k, ch -> if (ch == ' ') mask[k] = false }
        return mask
    }
}
