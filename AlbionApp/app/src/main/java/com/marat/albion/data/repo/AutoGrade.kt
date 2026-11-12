package com.marat.albion.data.repo

import com.marat.albion.data.db.ReviewStateEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.games.Verdict

/** Откуда пришёл ответ в журнале повторов (`review_log.source`). */
object ReviewSource {
    /** Карточка «Сегодня» с самооценкой. */
    const val CARD = "card"
    /** «Сегодня»: зрелое слово — ввод RU → EN. */
    const val CARD_TYPED = "card:typed"
    /** «Сегодня»: зрелое слово — пропуск в примере. */
    const val CARD_CLOZE = "card:cloze"

    /** Тренировка из «Практики»: `game:<Game.name>`. */
    fun game(name: String) = "game:$name"
}

/**
 * Оценка FSRS по объективной проверке ввода, а не по самооценке. Засчитываются только
 * проверяемые вводом форматы: выбор варианта, порядок плашек и шэдоуинг сюда не попадают.
 */
object AutoGrade {
    /** Верно без подсказок — «Хорошо»; опечатка или подсказка — «Трудно»; ошибка — «Снова». */
    fun rating(verdict: Verdict, hinted: Boolean): Rating = when {
        verdict == Verdict.Wrong -> Rating.Again
        verdict == Verdict.Typo || hinted -> Rating.Hard
        else -> Rating.Good
    }

    /**
     * Ответ в тренировке идёт в FSRS, только если слово уже в повторении и срок подошёл:
     * новое слово учится в «Сегодня», а досрочный ответ сбил бы интервал.
     */
    fun eligible(state: ReviewStateEntity?, now: Long): Boolean =
        state != null && state.state == CardState.REVIEW && state.due <= now
}
