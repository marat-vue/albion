package com.marat.albion.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marat.albion.data.repo.StudySource
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.games.Cloze
import com.marat.albion.data.games.ClozeTask
import com.marat.albion.data.repo.CardFormat
import com.marat.albion.data.repo.Leech
import com.marat.albion.data.repo.ReviewSource
import com.marat.albion.data.repo.StudyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudyCard(
    val card: CardEntity,
    val irregular: IrregularData?,
    val isNew: Boolean,
    /** Карточка пришла из учебных шагов (а не из повторений/новых). */
    val fromLearning: Boolean,
    /** Подписи интервалов для кнопок оценки. */
    val intervals: Map<Rating, String>,
    /** Сколько раз слово забывалось. */
    val lapses: Int = 0,
    /** «Трудное» слово: забывалось много раз и не закрепилось (см. [Leech]). */
    val leech: Boolean = false,
    /** Формат по зрелости: классика с самооценкой, ввод RU → EN или пропуск в примере. */
    val format: CardFormat = CardFormat.Classic,
    val cloze: ClozeTask? = null,
    /** Засчитанные раньше варианты ответа («Мой ответ тоже верен»). */
    val ownAnswers: List<String> = emptyList(),
)

data class StudyState(
    val loading: Boolean = true,
    val current: StudyCard? = null,
    val revealed: Boolean = false,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val answered: Int = 0,
    val again: Int = 0,
    val canUndo: Boolean = false,
    val finished: Boolean = false,
    val elapsedMs: Long = 0,
    /** Последняя оценка — от неё зависит, куда «улетает» карточка. */
    val lastRating: Rating? = null,
    val error: String? = null,
    /** A short session counts every saved answer, including learning repeats. */
    val answerLimit: Int? = null,
    val targetAnswers: Int = 0,
) {
    val remaining get() = newCount + learningCount + reviewCount
    val limitReached get() = answerLimit != null && answered >= answerLimit
    val progress get() = if (answerLimit != null) {
        if (targetAnswers == 0) 0f else (answered.toFloat() / targetAnswers).coerceIn(0f, 1f)
    } else if (answered + remaining == 0) 0f else answered.toFloat() / (answered + remaining)
}

/**
 * Занятие по словарю, коллекции или «Сегодня» (см. [StudySource]). Очередь как в Anki: учебные шаги (1 и 10 минут),
 * подошедшие по времени, идут первыми; новые карточки равномерно вкраплены в повторения.
 */
class StudyViewModel(private val repo: StudyRepository, private val source: StudySource, answerLimit: Int? = null) : ViewModel() {

    private val _state = MutableStateFlow(StudyState(answerLimit = answerLimit?.coerceIn(1, 100)))
    val state: StateFlow<StudyState> = _state.asStateFlow()

    private val learning = mutableListOf<Pair<CardEntity, Long>>() // карточка → когда показать
    private val reviews = ArrayDeque<CardEntity>()
    private val news = ArrayDeque<CardEntity>()
    private var reviewsSinceNew = 0
    private var answering = false
    private var shownAt = 0L
    private var sessionStart = System.currentTimeMillis()

    private data class UndoEntry(val logId: Long, val card: CardEntity, val rating: Rating)
    private val undoStack = ArrayDeque<UndoEntry>()

    init {
        viewModelScope.launch {
            val q = try {
                repo.buildQueue(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Albion", "Очередь занятия", e)
                _state.update { it.copy(loading = false, finished = true, error = "Не удалось подготовить занятие. Попробуйте ещё раз.") }
                return@launch
            }
            q.learning.forEach { card -> learning += card to repo.memory(card.id).due }
            reviews.addAll(q.reviews)
            news.addAll(q.news.filter { n -> learning.none { it.first.id == n.id } })
            _state.update { it.copy(targetAnswers = it.answerLimit ?: (q.learning.size + q.reviews.size + q.news.size)) }
            showNext()
        }
    }

    fun reveal() {
        _state.update { it.copy(revealed = true) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun saveFailed(message: String, cause: Exception) {
        android.util.Log.e("Albion", message, cause)
        answering = false
        _state.update { it.copy(error = message, canUndo = undoStack.isNotEmpty()) }
    }

    /** Ответ на зрелое слово вводом: оценку ставит проверка, источник — формат карточки. */
    fun answerChecked(rating: Rating) {
        val current = _state.value.current ?: return
        answer(rating, if (current.format == CardFormat.Cloze) ReviewSource.CARD_CLOZE else ReviewSource.CARD_TYPED)
    }

    /** «Мой ответ тоже верен»: вариант запоминается для этого слова, ответ засчитывается как «Хорошо». */
    fun acceptOwnAnswer(answer: String) {
        val current = _state.value.current ?: return
        if (answering || answer.isBlank()) return
        viewModelScope.launch {
            try {
                repo.addOwnAnswer(current.card, answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Albion", "Свой вариант ответа", e)
            }
            answerChecked(Rating.Good)
        }
    }

    fun answer(rating: Rating, source: String = ReviewSource.CARD) {
        val current = _state.value.current ?: return
        if (!_state.value.revealed || answering) return
        answering = true
        _state.update { it.copy(canUndo = false, error = null) }
        viewModelScope.launch {
            val duration = System.currentTimeMillis() - shownAt
            val (logId, memory) = try {
                repo.answer(current.card, rating, duration, source = source)
            } catch (e: CancellationException) {
                answering = false
                throw e
            } catch (e: Exception) {
                saveFailed("Не удалось сохранить ответ. Попробуйте ещё раз.", e)
                return@launch
            }
            undoStack.addLast(UndoEntry(logId, current.card, rating))
            if (undoStack.size > 20) undoStack.removeFirst()
            if (memory.state == CardState.LEARNING || memory.state == CardState.RELEARNING) {
                learning += current.card to memory.due
            }
            _state.update {
                it.copy(
                    answered = it.answered + 1,
                    again = it.again + if (rating == Rating.Again) 1 else 0,
                    lastRating = rating,
                )
            }
            showNext()
        }
    }

    /** Трудное слово — отложить на неделю. Это не ответ: в журнал и в «Отменить» не попадает. */
    fun postpone() {
        val current = _state.value.current ?: return
        if (answering) return
        answering = true
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.postpone(current.card)
            } catch (e: CancellationException) {
                answering = false
                throw e
            } catch (e: Exception) {
                saveFailed("Не удалось отложить слово. Попробуйте ещё раз.", e)
                return@launch
            }
            _state.update { it.copy(lastRating = null) }
            showNext()
        }
    }

    fun undo() {
        if (answering) return
        val entry = undoStack.lastOrNull() ?: return
        answering = true
        _state.update { it.copy(canUndo = false, error = null) }
        viewModelScope.launch {
            val card = try {
                repo.undo(entry.logId) ?: entry.card
            } catch (e: CancellationException) {
                answering = false
                throw e
            } catch (e: Exception) {
                saveFailed("Не удалось отменить ответ. Попробуйте ещё раз.", e)
                return@launch
            }
            undoStack.removeLast()
            learning.removeAll { it.first.id == card.id }
            // Текущая (ещё не отвеченная) карточка возвращается в начало своей очереди.
            _state.value.current?.takeIf { it.card.id != card.id }?.let { cur ->
                when {
                    cur.fromLearning -> learning += cur.card to repo.memory(cur.card.id).due
                    cur.isNew -> news.addFirst(cur.card)
                    else -> reviews.addFirst(cur.card)
                }
            }
            _state.update {
                it.copy(
                    answered = (it.answered - 1).coerceAtLeast(0),
                    again = (it.again - if (entry.rating == Rating.Again) 1 else 0).coerceAtLeast(0),
                    lastRating = null,
                )
            }
            present(card, fromLearning = repo.memory(card.id).state.let { it == CardState.LEARNING || it == CardState.RELEARNING })
        }
    }

    private suspend fun showNext() {
        if (_state.value.limitReached) {
            answering = false
            _state.update { it.copy(
                loading = false, current = null, finished = true, canUndo = undoStack.isNotEmpty(),
                newCount = news.size, learningCount = learning.size, reviewCount = reviews.size,
                elapsedMs = System.currentTimeMillis() - sessionStart,
            ) }
            return
        }
        val now = System.currentTimeMillis()
        learning.sortBy { it.second }
        val dueLearning = learning.firstOrNull()?.takeIf { it.second <= now }
        var fromLearning = true
        val next: CardEntity? = when {
            dueLearning != null -> { learning.remove(dueLearning); dueLearning.first }
            reviews.isNotEmpty() || news.isNotEmpty() -> { fromLearning = false; pickReviewOrNew() }
            learning.isNotEmpty() && learning.first().second <= now + StudyRepository.LEARN_AHEAD -> learning.removeAt(0).first
            else -> null
        }
        if (next == null) {
            answering = false
            _state.update {
                it.copy(
                    loading = false, current = null, finished = true, canUndo = undoStack.isNotEmpty(),
                    newCount = 0, learningCount = learning.size, reviewCount = 0,
                    elapsedMs = System.currentTimeMillis() - sessionStart,
                )
            }
            return
        }
        present(next, fromLearning)
    }

    private fun pickReviewOrNew(): CardEntity {
        val ratio = if (news.isEmpty()) Int.MAX_VALUE else maxOf(1, reviews.size / news.size)
        return if (reviews.isNotEmpty() && (news.isEmpty() || reviewsSinceNew < ratio)) {
            reviewsSinceNew++
            reviews.removeFirst()
        } else {
            reviewsSinceNew = 0
            news.removeFirst()
        }
    }

    private suspend fun present(card: CardEntity, fromLearning: Boolean) {
        val memory = repo.memory(card.id)
        val now = System.currentTimeMillis()
        val format = repo.cardFormat(card, memory, now)
        val fsrs = repo.scheduler()
        val preview = fsrs.preview(memory, now)
        val intervals = preview.mapValues { (_, m) ->
            if (m.state == CardState.REVIEW) {
                // Повторение в днях: показываем дни от начала учебного дня, как будет сохранено.
                val days = maxOf(1, Math.round((m.due - now).toDouble() / Fsrs.DAY).toInt())
                Fsrs.formatInterval(days * Fsrs.DAY)
            } else Fsrs.formatInterval(m.due - now)
        }
        shownAt = now
        answering = false
        _state.update {
            it.copy(
                loading = false,
                finished = false,
                revealed = false,
                current = StudyCard(card, repo.irregular(card), memory.state == CardState.NEW, fromLearning, intervals,
                    lapses = memory.lapses, leech = Leech.isLeech(memory.lapses, memory.stability),
                    format = format, cloze = if (format == CardFormat.Cloze) Cloze.make(card) else null,
                    ownAnswers = if (format == CardFormat.Typed) repo.ownAnswers(card) else emptyList()),
                newCount = news.size,
                learningCount = learning.size,
                reviewCount = reviews.size,
                canUndo = undoStack.isNotEmpty(),
            )
        }
    }
}
