package com.marat.albion.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Коллекция пользователя: набор слов из любых словарей, по которому можно заниматься отдельно. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Слово коллекции. Слово задаётся словарём и термином — как в WordCards.group, — поэтому
 * обновление встроенных таблиц не ломает состав коллекции. Память FSRS у слова одна на все коллекции.
 */
@Entity(
    tableName = "collection_words",
    primaryKeys = ["collection_id", "deck", "term"],
    indices = [Index("deck", "term")],
)
data class CollectionWordEntity(
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    val deck: String,
    /** WordCards.key(term). */
    val term: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY created_at, id")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY created_at, id")
    suspend fun all(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collection_words")
    fun observeWords(): Flow<List<CollectionWordEntity>>

    @Query("SELECT * FROM collection_words")
    suspend fun words(): List<CollectionWordEntity>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Transaction
    suspend fun delete(id: Long) {
        clear(id)
        deleteRow(id)
    }

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM collection_words WHERE collection_id = :id")
    suspend fun clear(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWords(words: List<CollectionWordEntity>)

    @Query("DELETE FROM collection_words WHERE collection_id = :id AND deck = :deck AND term = :term")
    suspend fun removeWord(id: Long, deck: String, term: String)

    /** Слово удалено из словаря (личные слова из книг) — убираем его из всех коллекций. */
    @Query("DELETE FROM collection_words WHERE deck = :deck AND term = :term")
    suspend fun forgetWord(deck: String, term: String)
}
