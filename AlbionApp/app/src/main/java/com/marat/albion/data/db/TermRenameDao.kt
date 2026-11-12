package com.marat.albion.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Перенос учебной истории при переименовании термина (см. content/TermRenames). Схему не меняет. */
@Dao
interface TermRenameDao {
    @Query("UPDATE OR IGNORE review_state SET card_id = :new WHERE card_id = :old")
    suspend fun moveState(old: String, new: String)

    @Query("UPDATE review_log SET card_id = :new WHERE card_id = :old")
    suspend fun moveLogs(old: String, new: String)

    @Query("UPDATE OR IGNORE game_card_stats SET card_id = :new WHERE card_id = :old")
    suspend fun moveGameStats(old: String, new: String)

    @Query("DELETE FROM game_card_stats WHERE card_id = :old")
    suspend fun dropGameStats(old: String)

    @Query("UPDATE game_answer_log SET item_id = :new WHERE item_id = :old")
    suspend fun moveGameAnswers(old: String, new: String)

    @Query("UPDATE OR IGNORE collection_words SET term = :newKey WHERE deck = :deck AND term = :oldKey")
    suspend fun moveCollectionWords(deck: String, oldKey: String, newKey: String)

    @Query("UPDATE OR IGNORE leech_hints SET card_id = :new WHERE card_id = :old")
    suspend fun moveLeechHint(old: String, new: String)

    @Query("DELETE FROM leech_hints WHERE card_id = :old")
    suspend fun dropLeechHint(old: String)

    @Query("DELETE FROM collection_words WHERE deck = :deck AND term = :oldKey")
    suspend fun dropCollectionWords(deck: String, oldKey: String)

    /** Вызывается до удаления карточки со старым id: её состояние ещё на месте. */
    @Transaction
    suspend fun moveCard(old: String, new: String) {
        moveState(old, new)
        moveLogs(old, new)
        moveGameStats(old, new)
        dropGameStats(old)
        moveGameAnswers(old, new)
        moveLeechHint(old, new)
        dropLeechHint(old)
    }

    /** Коллекции хранят слово по ключу термина, а не по id карточки. */
    @Transaction
    suspend fun moveCollectionTerm(deck: String, oldKey: String, newKey: String) {
        moveCollectionWords(deck, oldKey, newKey)
        dropCollectionWords(deck, oldKey)
    }
}
