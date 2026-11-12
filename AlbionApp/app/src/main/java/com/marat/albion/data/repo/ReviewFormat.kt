package com.marat.albion.data.repo

import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Memory
import com.marat.albion.data.games.Cloze

/** Как показать слово в занятии. Память FSRS одна на слово при любом формате. */
enum class CardFormat {
    /** EN → RU с самооценкой. */
    Classic,
    /** RU → EN: ввод слова по значению, оценка автоматически. */
    Typed,
    /** Пропуск в примере карточки, оценка автоматически. */
    Cloze,
}

/**
 * Формат по зрелости: молодое слово узнаём, окрепшее — вспоминаем сами. Пропуск в примере
 * подсказывает контекстом, поэтому его даём раньше, чем ввод по одному значению.
 */
object ReviewFormat {
    const val CLOZE_FROM_DAYS = 7.0
    const val TYPED_FROM_DAYS = 10.0

    /** Слова с неправильными формами учат именно формы — им оставляем обычную карточку. */
    private val classicOnly = setOf(Deck.IRREGULAR.id)

    /**
     * [day] — номер учебного дня: в один день формат слова не меняется (например, после «Отменить»),
     * а в разные дни чередуется. При наличии примера cloze идёт примерно в каждом третьем показе.
     */
    fun choose(card: CardEntity, memory: Memory, day: Long): CardFormat {
        if (memory.state != CardState.REVIEW || card.deck in classicOnly) return CardFormat.Classic
        val hasCloze = Cloze.make(card) != null
        return when {
            memory.stability >= TYPED_FROM_DAYS ->
                if (hasCloze && Math.floorMod((card.id + day).hashCode(), 3) == 0) CardFormat.Cloze else CardFormat.Typed
            memory.stability >= CLOZE_FROM_DAYS && hasCloze -> CardFormat.Cloze
            else -> CardFormat.Classic
        }
    }
}
