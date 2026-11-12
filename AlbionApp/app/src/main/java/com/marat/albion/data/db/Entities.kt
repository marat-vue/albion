package com.marat.albion.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.marat.albion.data.grammar.GrammarPrompt

/**
 * Карточка — материал из xlsx или личное слово из книги. Импорт обновляет только
 * встроенные словари; прогресс хранится отдельно в [ReviewStateEntity].
 *
 * Для xlsx id = "<deck>:<term>#<n>"; личные слова используют ключи слова и значения.
 */
@Entity(tableName = "cards", indices = [Index("deck"), Index("term")])
data class CardEntity(
    @PrimaryKey val id: String,
    val deck: String,
    val position: Int,
    val term: String,
    val ipa: String,
    val pos: String,
    val level: String?,
    @ColumnInfo(name = "meaning_en") val meaningEn: String,
    @ColumnInfo(name = "meaning_ru") val meaningRu: String,
    val example1: String,
    val example2: String,
    /** Имя mp3-файла внутри assets/content/audio/<deck>/ или null. */
    val audio: String?,
    /** JSON IrregularData для глаголов или BookWordSource для личных слов из книг. */
    val extra: String?,
) {
    /** Source rows are retained in Room; the UI and scheduler use one combined word. */
    @Ignore
    var senses: List<CardEntity> = emptyList()
        internal set
}

/** Состояние FSRS. Отсутствие строки = карточка ещё новая. */
@Entity(tableName = "review_state", indices = [Index("deck"), Index("due")])
data class ReviewStateEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: String,
    val deck: String,
    val state: Int,
    val step: Int,
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    @ColumnInfo(name = "last_review") val lastReview: Long,
    val reps: Int,
    val lapses: Int,
)

@Entity(tableName = "review_log", indices = [Index("card_id"), Index("reviewed_at")])
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    val deck: String,
    val rating: Int,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    /** Состояние карточки до ответа (0 — новая). */
    @ColumnInfo(name = "state_before") val stateBefore: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** Снимок состояния до ответа — для «Отменить». null, если карточка была новой. */
    @ColumnInfo(name = "prev_stability") val prevStability: Double?,
    @ColumnInfo(name = "prev_difficulty") val prevDifficulty: Double?,
    @ColumnInfo(name = "prev_due") val prevDue: Long?,
    @ColumnInfo(name = "prev_last_review") val prevLastReview: Long?,
    @ColumnInfo(name = "prev_step") val prevStep: Int?,
    @ColumnInfo(name = "prev_reps") val prevReps: Int?,
    @ColumnInfo(name = "prev_lapses") val prevLapses: Int?,
    /** Откуда ответ: карточка с самооценкой, ввод в «Сегодня» или тренировка (см. ReviewSource). */
    @ColumnInfo(defaultValue = "card") val source: String = "card",
)

@Entity(tableName = "grammar_progress")
data class GrammarProgressEntity(
    @PrimaryKey @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "read_at") val readAt: Long?,
    val bookmarked: Boolean,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    @ColumnInfo(name = "mastered_at") val masteredAt: Long? = null,
    @ColumnInfo(name = "quiz_at") val quizAt: Long? = null,
    @ColumnInfo(name = "quiz_correct") val quizCorrect: Int? = null,
    @ColumnInfo(name = "quiz_total") val quizTotal: Int? = null,
    @ColumnInfo(name = "review_stage", defaultValue = "0") val reviewStage: Int = 0,
    @ColumnInfo(name = "review_at") val reviewAt: Long? = null,
)

/** Грамматическое задание из grammar_tasks.xlsx. [options] — варианты, каждый на своей строке. */
@Entity(tableName = "grammar_tasks", indices = [Index("level"), Index("kind")])
data class GrammarTaskEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val level: String,
    val topic: Int?,
    @ColumnInfo(name = "topic_title") val topicTitle: String,
    /** "choice" — выбор варианта, "input" — вписать пропуск. */
    val kind: String,
    val prompt: String,
    val options: String,
    /**
     * Один пропуск: варианты ввода через « / ». Несколько пропусков: части через
     * « / », каждый альтернативный набор для ввода — с новой строки.
     */
    val answer: String,
    val explanation: String,
) {
    val optionList: List<String> get() = options.lines().filter { it.isNotBlank() }
    val acceptedAnswers: List<String> get() {
        val parsed = GrammarPrompt(prompt)
        val variants = when {
            kind == "choice" -> listOf(answer)
            parsed.gapCount > 1 -> answer.lines()
            else -> answer.split(" / ")
        }
        return variants.map { it.trim() }.filter { it.isNotEmpty() }.map { value ->
            if (kind == "input" && parsed.gapCount > 1) parsed.answerParts(value)?.joinToString(" / ") ?: value else value
        }
    }
    /** Полный правильный пример для разбора ошибок; задания без пропусков показывают сам ответ. */
    val correctAnswerText: String get() {
        val correct = acceptedAnswers.firstOrNull() ?: answer
        return GrammarPrompt(prompt).complete(correct) ?: correct
    }
    /** Тема 123 текущего учебника проверяет заглавные буквы, апостроф и пунктуацию. */
    val requiresExactWriting: Boolean get() = topic == 123
    /** Запятая — суть задания: тема 123 или задание/пояснение говорит о запятых («Поставьте запятую…»). */
    val commasMatter: Boolean get() = requiresExactWriting ||
        prompt.contains("запят", ignoreCase = true) || explanation.contains("запят", ignoreCase = true)
}

/** Статистика по заданию — чтобы чаще давать те, где ошибались. */
@Entity(tableName = "grammar_task_stats")
data class GrammarTaskStatEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    val correct: Int,
    val wrong: Int,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
)

/** Книга библиотеки. Текст хранится отдельным файлом в памяти приложения. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val level: String,
    /** "asset" — встроенная, "user" — добавлена пользователем. */
    val source: String,
    @ColumnInfo(name = "source_size") val sourceSize: Long,
    val chapters: Int,
    val chars: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Где пользователь остановился. */
@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    val chapter: Int,
    val paragraph: Int,
    val offset: Int,
    /** Доля прочитанного 0…1. */
    val progress: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("book_id")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: String,
    val chapter: Int,
    val paragraph: Int,
    val offset: Int,
    val snippet: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Результаты мини-игр по слову: в каждой игре своя статистика. Слово — «ошибка»,
 * пока после последнего промаха не дано два верных ответа подряд ([streak] < 2).
 */
@Entity(tableName = "game_card_stats", primaryKeys = ["card_id", "game"], indices = [Index("game")])
data class GameCardStatEntity(
    @ColumnInfo(name = "card_id") val cardId: String,
    val game: String,
    val correct: Int,
    val wrong: Int,
    /** Верных ответов подряд после последней ошибки. */
    val streak: Int,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
) {
    val isMistake get() = wrong > 0 && streak < GameStats.CLEAR_AFTER
}

object GameStats {
    /** Сколько верных ответов подряд снимают слово из ошибок. */
    const val CLEAR_AFTER = 2
}

/** Выделение в книге (внутри одного абзаца) и заметка к нему. */
@Entity(tableName = "highlights", indices = [Index("book_id")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: String,
    val chapter: Int,
    val paragraph: Int,
    /** Символы абзаца [start, end). */
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Агрегаты для экранов словарей. */
data class DeckCounts(
    val deck: String,
    val total: Int,
    val started: Int,
    /** Повторения (state = 2), подошедшие к сроку. */
    val due: Int,
    val learning: Int,
    val mature: Int,
)

data class DayCount(val day: Long, val count: Int)

data class DayAnswer(val cardId: String, val deck: String, val stateBefore: Int)

/** Статья офлайн-словаря EN → RU (FreeDict / Викисловарь), встроенного в приложение. */
@Entity(tableName = "dictionary", indices = [Index("term")])
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Слово в нижнем регистре — ключ поиска. */
    val term: String,
    val headword: String,
    val pos: String,
    /** Значения через «; », синонимы внутри значения — через «, ». */
    val text: String,
)

/**
 * Перевод предложения-примера на русский для «Соберите предложение». Модель на устройстве переводит
 * один раз; плохой перевод можно поправить вручную — тогда [edited] защищает его от перезаписи.
 */
@Entity(tableName = "sentence_translations")
data class SentenceTranslationEntity(
    @PrimaryKey val text: String,
    val translation: String,
    val edited: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
