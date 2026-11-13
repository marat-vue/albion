package com.marat.albion.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Разговор с ИИ: сценарий и уровень задают системную подсказку на всё время беседы. */
@Entity(tableName = "conversations", indices = [Index("updated_at")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** [com.marat.albion.data.conversation.Scenario.id]. */
    val scenario: String,
    /** CEFR: A2, B1, B2, C1. */
    val level: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Пересказ ранних реплик, которые уже не помещаются в подсказку модели. */
    @ColumnInfo(defaultValue = "") val summary: String = "",
    /** id последнего сообщения, вошедшего в [summary]; 0 — беседа ещё не сжималась. */
    @ColumnInfo(name = "summary_up_to", defaultValue = "0") val summaryUpTo: Long = 0,
)

@Entity(
    tableName = "conversation_messages",
    indices = [Index("conversation_id")],
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversation_id"], onDelete = ForeignKey.CASCADE)],
)
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** "user" или "assistant". */
    val role: String,
    val text: String,
    /** Сообщение надиктовано голосом. */
    val voice: Boolean = false,
    /** Разбор фразы ученика: null — ещё не проверена, "" — ошибок нет, иначе «исправление\nпояснение». */
    val correction: String? = null,
    /** Русский перевод реплики собеседника, если его открывали. */
    val translation: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val fromUser get() = role == USER

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class ConversationSummary(
    @Embedded val conversation: ConversationEntity,
    val preview: String?,
    /** Сколько реплик написал ученик. */
    val turns: Int,
    /** Сколько его реплик модель поправила. */
    val corrections: Int,
)

@Dao
interface ConversationDao {
    @Query(
        """SELECT c.*,
            (SELECT m.text FROM conversation_messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS preview,
            (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = c.id AND m.role = 'user') AS turns,
            (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = c.id AND m.role = 'user'
                AND m.correction IS NOT NULL AND m.correction != '') AS corrections
        FROM conversations c ORDER BY c.updated_at DESC"""
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :id ORDER BY id")
    fun observeMessages(id: Long): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :id ORDER BY id")
    suspend fun messages(id: Long): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages WHERE id = :id")
    suspend fun message(id: Long): ConversationMessageEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Insert
    suspend fun insert(message: ConversationMessageEntity): Long

    @Query("UPDATE conversations SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE conversations SET summary = :summary, summary_up_to = :upTo WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, upTo: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversation_messages SET correction = :correction WHERE id = :id")
    suspend fun setCorrection(id: Long, correction: String)

    @Query("DELETE FROM conversation_messages WHERE id >= :fromId AND conversation_id = :conversationId")
    suspend fun deleteFrom(conversationId: Long, fromId: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}
