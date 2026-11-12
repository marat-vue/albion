package com.marat.albion.data.repo

import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.ReviewStateEntity

/**
 * «Трудное» слово (leech): забывалось много раз и так и не закрепилось. Самооценка на нём
 * не помогает — такое слово показываем дозированно и предлагаем отложить или переработать.
 */
object Leech {
    const val MIN_LAPSES = 4
    /** Стабильность в днях, ниже которой слово «не держится». */
    const val MAX_STABILITY = 3.0
    /** Сколько трудных слов за занятие: больше — только выматывает. */
    const val PER_SESSION = 2
    /** После стольких забываний карточку стоит переписать или убрать. */
    const val REWRITE_LAPSES = 8
    const val POSTPONE_DAYS = 7

    fun isLeech(state: ReviewStateEntity?): Boolean = isLeech(state?.lapses ?: 0, state?.stability ?: Double.MAX_VALUE)

    fun isLeech(lapses: Int, stability: Double): Boolean = lapses >= MIN_LAPSES && stability < MAX_STABILITY

    /** Оставляет не больше [PER_SESSION] трудных слов, остальные ждут следующего занятия. */
    fun limit(cards: List<CardEntity>, state: (CardEntity) -> ReviewStateEntity?): List<CardEntity> {
        var leeches = 0
        return cards.filter { !isLeech(state(it)) || leeches++ < PER_SESSION }
    }
}
