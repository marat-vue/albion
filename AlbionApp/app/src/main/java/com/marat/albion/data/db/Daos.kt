package com.marat.albion.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY position, id")
    suspend fun all(): List<CardEntity>

    @Query("SELECT * FROM cards ORDER BY position, id")
    fun observeAll(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE deck = :deck ORDER BY position, id")
    suspend fun inDeck(deck: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE deck = (SELECT deck FROM cards WHERE id = :id) AND lower(trim(term)) = (SELECT lower(trim(term)) FROM cards WHERE id = :id) ORDER BY position, id")
    suspend fun wordMembers(id: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE deck = (SELECT deck FROM cards WHERE id = :id) AND lower(trim(term)) = (SELECT lower(trim(term)) FROM cards WHERE id = :id) ORDER BY position, id")
    fun observeWordMembers(id: String): Flow<List<CardEntity>>

    @Upsert
    suspend fun upsert(cards: List<CardEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(card: CardEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM cards WHERE deck = :deck")
    suspend fun nextPosition(deck: String): Int

    @Query("SELECT * FROM cards WHERE deck = :deck AND id LIKE :prefix || '%' ORDER BY position")
    fun observeWithPrefix(deck: String, prefix: String): Flow<List<CardEntity>>

    @Query("SELECT id FROM cards WHERE deck = :deck")
    suspend fun idsOf(deck: String): List<String>

    @Query("DELETE FROM cards WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun get(id: String): CardEntity?

    @Query("SELECT COUNT(*) FROM cards")
    fun observeTotal(): Flow<Int>

    @Query("SELECT * FROM game_card_stats WHERE card_id = :cardId AND game = :game")
    suspend fun gameStat(cardId: String, game: String): GameCardStatEntity?

    @Query("SELECT * FROM game_card_stats")
    suspend fun gameStats(): List<GameCardStatEntity>

    @Query("SELECT * FROM game_card_stats")
    fun observeGameStats(): Flow<List<GameCardStatEntity>>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM review_state WHERE card_id = :cardId)
            OR EXISTS(SELECT 1 FROM review_log WHERE card_id = :cardId)
            OR EXISTS(SELECT 1 FROM game_card_stats WHERE card_id = :cardId)
            OR EXISTS(SELECT 1 FROM game_answer_log WHERE item_id = :cardId)
    """)
    suspend fun hasLearningActivity(cardId: String): Boolean

    @Upsert
    suspend fun upsertGameStat(stat: GameCardStatEntity)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_state")
    suspend fun states(): List<ReviewStateEntity>

    @Query("SELECT * FROM review_state")
    fun observeStates(): Flow<List<ReviewStateEntity>>

    @Query("SELECT * FROM review_state WHERE card_id IN (:ids)")
    suspend fun statesFor(ids: List<String>): List<ReviewStateEntity>

    @Query("SELECT COUNT(*) FROM review_log WHERE card_id IN (:ids)")
    suspend fun countLogsFor(ids: List<String>): Int

    @Query("SELECT * FROM review_state WHERE card_id = :id")
    suspend fun state(id: String): ReviewStateEntity?

    @Upsert
    suspend fun upsertState(state: ReviewStateEntity)

    @Query("DELETE FROM review_state WHERE card_id = :id")
    suspend fun deleteState(id: String)

    @Query("DELETE FROM review_state WHERE card_id NOT IN (SELECT id FROM cards)")
    suspend fun deleteOrphanStates()

    @Insert
    suspend fun insertLog(log: ReviewLogEntity): Long

    @Query("SELECT * FROM review_log WHERE id = :id")
    suspend fun log(id: Long): ReviewLogEntity?

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun deleteLog(id: Long)

    /** Ответы с начала учебного дня — из них считаются дневные лимиты словарей и коллекций. */
    @Query("SELECT card_id AS cardId, deck, state_before AS stateBefore FROM review_log WHERE reviewed_at >= :since")
    suspend fun answersSince(since: Long): List<DayAnswer>

    @Query("SELECT COUNT(*) FROM review_log WHERE reviewed_at >= :since")
    fun observeReviewsSince(since: Long): Flow<Int>

    /** Повторения по «учебным дням». День = floor((t + shift) / сутки), shift учитывает пояс и час начала дня. */
    @Query(
        """
        SELECT ((reviewed_at + :shift) / 86400000) AS day, COUNT(*) AS count
        FROM review_log WHERE reviewed_at >= :since
        GROUP BY day ORDER BY day
        """
    )
    fun observeDaily(since: Long, shift: Long): Flow<List<DayCount>>

    @Query("SELECT DISTINCT ((reviewed_at + :shift) / 86400000) AS day FROM review_log ORDER BY day DESC LIMIT 1000")
    fun observeActiveDays(shift: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM review_log WHERE state_before = 2 AND reviewed_at >= :since")
    fun observeReviewAnswers(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_log WHERE state_before = 2 AND rating > 1 AND reviewed_at >= :since")
    fun observeReviewPassed(since: Long): Flow<Int>

    /** Ответы на повторения и сколько из них вспомнили — для удержания за неделю. */
    @Query("SELECT COUNT(*) AS answers, COALESCE(SUM(CASE WHEN rating > 1 THEN 1 ELSE 0 END), 0) AS passed FROM review_log WHERE state_before = 2 AND reviewed_at >= :since")
    suspend fun reviewPassSince(since: Long): ReviewPassCount

    @Query("SELECT COUNT(*) FROM review_log")
    fun observeTotalReviews(): Flow<Int>

    /** Весь журнал — для подстройки параметров FSRS. */
    @Query("SELECT card_id AS cardId, rating, reviewed_at AS time, state_before AS stateBefore FROM review_log ORDER BY card_id, reviewed_at")
    suspend fun allEvents(): List<com.marat.albion.data.fsrs.ReviewEvent>

    @Query("SELECT COUNT(*) FROM review_log WHERE reviewed_at >= :since")
    suspend fun countAllSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM review_log WHERE reviewed_at >= :since")
    fun observeTimeSince(since: Long): Flow<Long>

    /**
     * Сброс колоды: карточки снова новые. Журнал ответов остаётся — на нём держатся серия дней,
     * активность и удержание; оптимизатор FSRS начинает историю заново с ответа на новую карточку.
     */
    @Query("DELETE FROM review_state WHERE deck = :deck")
    suspend fun resetDeck(deck: String)

    @Query("DELETE FROM review_log WHERE card_id = :cardId")
    suspend fun deleteCardLogs(cardId: String)
}

@Dao
interface GrammarDao {
    @Query("SELECT * FROM grammar_progress")
    fun observeAll(): Flow<List<GrammarProgressEntity>>

    @Query("SELECT * FROM grammar_progress WHERE topic_id = :id")
    suspend fun get(id: String): GrammarProgressEntity?

    @Query("SELECT * FROM grammar_progress WHERE topic_id = :id")
    fun observe(id: String): Flow<GrammarProgressEntity?>

    @Upsert
    suspend fun upsert(entity: GrammarProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: GrammarProgressEntity)

    @Query("UPDATE grammar_progress SET opened_at = :now WHERE topic_id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("UPDATE grammar_progress SET read_at = CASE WHEN :read THEN COALESCE(read_at, :now) ELSE NULL END WHERE topic_id = :id")
    suspend fun setRead(id: String, read: Boolean, now: Long)

    @Query("UPDATE grammar_progress SET bookmarked = NOT bookmarked WHERE topic_id = :id")
    suspend fun toggleBookmark(id: String)

    @Query("UPDATE grammar_progress SET mastered_at = :masteredAt, quiz_at = :now, quiz_correct = :correct, quiz_total = :total, review_stage = :reviewStage, review_at = :reviewAt WHERE topic_id = :id")
    suspend fun recordQuiz(id: String, correct: Int, total: Int, now: Long, masteredAt: Long?, reviewStage: Int, reviewAt: Long)

    @Query("DELETE FROM grammar_progress")
    suspend fun clear()

    @Query("UPDATE grammar_progress SET read_at = NULL")
    suspend fun clearReadMarks()
}

@Dao
interface GrammarTaskDao {
    @Upsert
    suspend fun upsert(tasks: List<GrammarTaskEntity>)

    @Query("SELECT id FROM grammar_tasks")
    suspend fun ids(): List<String>

    @Query("DELETE FROM grammar_tasks WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM grammar_tasks WHERE kind = :kind")
    fun observeCount(kind: String): Flow<Int>

    /**
     * Задания для игры: сначала те, где чаще ошибались, затем новые, затем остальные;
     * внутри групп — случайно. [level] = "" — все уровни.
     */
    @Query(
        """
        SELECT t.* FROM grammar_tasks t LEFT JOIN grammar_task_stats s ON s.task_id = t.id
        WHERE t.kind = :kind AND (:level = '' OR t.level = :level)
        ORDER BY CASE
            WHEN s.task_id IS NOT NULL AND s.wrong > s.correct THEN 0
            WHEN s.task_id IS NULL THEN 1
            ELSE 2 END,
          RANDOM()
        LIMIT :limit
        """
    )
    suspend fun pool(kind: String, level: String, limit: Int): List<GrammarTaskEntity>

    /** Все задания одной темы учебника: сначала те, где ошибались, внутри — случайно. */
    @Query(
        """
        SELECT t.* FROM grammar_tasks t LEFT JOIN grammar_task_stats s ON s.task_id = t.id
        WHERE t.topic = :topic
        ORDER BY CASE WHEN s.task_id IS NOT NULL AND s.wrong > s.correct THEN 0 ELSE 1 END, RANDOM()
        """
    )
    suspend fun topicPool(topic: Int): List<GrammarTaskEntity>

    @Query("SELECT COUNT(*) FROM grammar_tasks WHERE topic = :topic")
    fun observeTopicCount(topic: Int): Flow<Int>

    @Query("SELECT DISTINCT topic FROM grammar_tasks WHERE topic IS NOT NULL")
    fun observeQuizTopics(): Flow<List<Int>>

    @Query("SELECT * FROM grammar_task_stats WHERE task_id = :id")
    suspend fun stat(id: String): GrammarTaskStatEntity?

    @Upsert
    suspend fun upsertStat(stat: GrammarTaskStatEntity)
}

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertBook(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY added_at")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM reading_positions")
    fun observePositions(): Flow<List<ReadingPositionEntity>>

    @Query("SELECT * FROM reading_positions WHERE book_id = :id")
    suspend fun position(id: String): ReadingPositionEntity?

    @Upsert
    suspend fun upsertPosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM bookmarks WHERE book_id = :id ORDER BY chapter, paragraph, `offset`")
    fun observeBookmarks(id: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM highlights WHERE book_id = :id ORDER BY chapter, paragraph, start")
    fun observeHighlights(id: String): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insertHighlight(h: HighlightEntity): Long

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateHighlightNote(id: Long, note: String)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("DELETE FROM highlights WHERE book_id = :id")
    suspend fun deleteHighlights(id: String)

    @Transaction
    suspend fun deleteBook(id: String) {
        deleteBookRow(id)
        deletePosition(id)
        deleteBookmarks(id)
        deleteHighlights(id)
    }

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookRow(id: String)

    @Query("DELETE FROM reading_positions WHERE book_id = :id")
    suspend fun deletePosition(id: String)

    @Query("DELETE FROM bookmarks WHERE book_id = :id")
    suspend fun deleteBookmarks(id: String)
}

/** Карточки по словам — для словарной справки в читалке. */
@Dao
interface LookupDao {
    @Query("SELECT * FROM cards WHERE lower(term) IN (:terms) ORDER BY CASE deck WHEN 'words' THEN 0 WHEN 'irregular_verbs' THEN 1 ELSE 2 END, position")
    suspend fun findByTerms(terms: List<String>): List<CardEntity>

    /** Термины начатых, но ещё не выученных карточек (стабильность меньше 21 дня) — для подсветки в книгах. */
    @Query(
        """
        SELECT c.term FROM cards c JOIN review_state r ON r.card_id = c.id
        WHERE NOT (r.state = 2 AND r.stability >= 21)
        """
    )
    fun observeStudiedTerms(): Flow<List<String>>

    @Query("SELECT extra FROM cards WHERE deck = 'irregular_verbs' AND extra IS NOT NULL")
    suspend fun irregularForms(): List<String>
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary WHERE term IN (:terms)")
    suspend fun find(terms: List<String>): List<DictionaryEntity>

    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun count(): Int

    @Query("DELETE FROM dictionary")
    suspend fun clear()

    @Insert
    suspend fun insert(rows: List<DictionaryEntity>)
}

@Dao
interface SentenceTranslationDao {
    @Query("SELECT * FROM sentence_translations WHERE text IN (:texts)")
    suspend fun find(texts: List<String>): List<SentenceTranslationEntity>

    /** Перевод модели не затирает поправленный вручную. */
    @Query("INSERT OR IGNORE INTO sentence_translations(text, translation, edited, updated_at) VALUES (:text, :translation, 0, :now)")
    suspend fun insertIfAbsent(text: String, translation: String, now: Long = System.currentTimeMillis())

    @androidx.room.Upsert
    suspend fun upsert(row: SentenceTranslationEntity)

    /** Начатые карточки колоды с примерами — их «Соберите предложение» берёт в первую очередь. */
    @Query(
        """
        SELECT c.* FROM cards c JOIN review_state r ON r.card_id = c.id
        WHERE c.deck = :deck AND (c.example1 != '' OR c.example2 != '')
        ORDER BY r.due LIMIT :limit
        """
    )
    suspend fun startedWithExamples(deck: String, limit: Int): List<CardEntity>
}

@Dao
interface LeechHintDao : com.marat.albion.data.repo.LeechHintStore {
    @Query("SELECT * FROM leech_hints WHERE card_id = :cardId")
    override suspend fun get(cardId: String): LeechHintEntity?

    @Query("SELECT * FROM leech_hints WHERE card_id = :cardId")
    override fun observe(cardId: String): Flow<LeechHintEntity?>

    @Upsert
    override suspend fun upsert(row: LeechHintEntity)

    @Query("DELETE FROM leech_hints WHERE card_id = :cardId")
    override suspend fun delete(cardId: String)
}
