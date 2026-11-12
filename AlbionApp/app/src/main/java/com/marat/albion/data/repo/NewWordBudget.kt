package com.marat.albion.data.repo

import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import kotlin.math.roundToInt

/**
 * Что известно о нагрузке на учебный день: начало дня (для прогноза) и удержание повторений
 * за последние 7 дней. По умолчанию — «ничего не известно», и бюджет равен настройке.
 */
data class StudyLoad(
    val dayStart: Long = 0,
    /** Ответов на повторения (state_before = REVIEW) за 7 дней. */
    val weekReviews: Int = 0,
    /** Из них вспомнили (оценка выше «Снова»). */
    val weekPassed: Int = 0,
) {
    val weekRetention: Float? get() = if (weekReviews == 0) null else weekPassed.toFloat() / weekReviews
}

/** Сколько новых слов дать сегодня и почему меньше обычного (для подписи на главном). */
data class NewBudget(val limit: Int, val reason: String? = null)

/**
 * Общий потолок новых слов «Сегодня» для всех коллекций. Базу из настроек уменьшаем, когда
 * удержание за неделю падает или впереди много повторений; выше базы не поднимаем никогда.
 */
object NewWordBudget {
    /** Удержание считаем по выборке не меньше стольких ответов. */
    const val MIN_SAMPLE = 20
    /** Одно новое слово в день даёт примерно столько повторений в день в установившемся режиме. */
    const val LOAD_PER_NEW = 8
    /** Даже при большой нагрузке понемногу идём вперёд. */
    const val FLOOR = 2
    /** Прогноз сглаживаем по ближайшим дням. */
    const val FORECAST_DAYS = 3

    fun compute(base: Int, load: StudyLoad, upcoming: List<Int>): NewBudget {
        if (base <= 0) return NewBudget(0)
        val retention = load.weekRetention?.takeIf { load.weekReviews >= MIN_SAMPLE }
        val retentionFactor = when {
            retention == null -> 1.0
            retention < 0.75f -> 0.5
            retention < 0.85f -> 0.75
            else -> 1.0
        }
        val target = base * LOAD_PER_NEW
        val ahead = if (upcoming.isEmpty()) 0.0 else upcoming.average()
        // Нагрузка выше цели — линейно уменьшаем до нуля при двойной цели.
        val loadFactor = if (ahead <= target) 1.0 else (1.0 - (ahead - target) / target).coerceIn(0.0, 1.0)
        val factor = retentionFactor * loadFactor
        if (factor >= 1.0) return NewBudget(base)
        val limit = (base * factor).roundToInt().coerceAtLeast(minOf(FLOOR, base)).coerceAtMost(base)
        val reason = if (loadFactor < retentionFactor) {
            "Впереди много повторений — новых слов сегодня $limit вместо $base."
        } else {
            "За неделю вспомнили ${((retention ?: 0f) * 100).roundToInt()}% повторений — новых слов сегодня $limit вместо $base, чтобы закрепить выученное."
        }
        return NewBudget(limit, reason)
    }

    /**
     * Повторения на ближайшие дни: завтра — всё, что подойдёт к концу завтрашнего дня
     * (включая несделанное сегодня), дальше — по дню.
     */
    fun forecast(states: Collection<ReviewStateEntity>, dayStart: Long, days: Int = FORECAST_DAYS): List<Int> {
        val counts = IntArray(days)
        states.forEach { s ->
            if (s.state != CardState.REVIEW) return@forEach
            val day = ((s.due - dayStart) / Fsrs.DAY).toInt()
            when {
                s.due < dayStart + 2 * Fsrs.DAY -> counts[0]++
                day in 2..days -> counts[day - 1]++
            }
        }
        return counts.toList()
    }
}
