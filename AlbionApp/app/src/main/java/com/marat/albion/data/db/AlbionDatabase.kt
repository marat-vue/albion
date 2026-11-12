package com.marat.albion.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CardEntity::class, ReviewStateEntity::class, ReviewLogEntity::class, GrammarProgressEntity::class,
        GrammarTaskEntity::class, GrammarTaskStatEntity::class,
        BookEntity::class, ReadingPositionEntity::class, BookmarkEntity::class, DictionaryEntity::class, HighlightEntity::class, GameCardStatEntity::class,
        GameAnswerLogEntity::class, ConversationEntity::class, ConversationMessageEntity::class,
        SentenceTranslationEntity::class, CollectionEntity::class, CollectionWordEntity::class,
        LeechHintEntity::class,
    ],
    version = 15,
    exportSchema = true,
    // v9: журнал игровых ответов; прежние суммарные результаты сохраняются.
    // v10: разговорная практика — беседы и их сообщения.
    // v11: пересказ ранней части беседы.
    // v12: сохранённые переводы предложений для «Соберите предложение».
    // v13: коллекции пользователя поверх словарей.
    // v14: источник ответа в журнале повторов (карточка, ввод, тренировка).
    // v15: мнемоники и контрастные примеры к трудным словам.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15)],
)
abstract class AlbionDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
    abstract fun reviews(): ReviewDao
    abstract fun gameActivity(): GameActivityDao
    abstract fun grammar(): GrammarDao
    abstract fun grammarTasks(): GrammarTaskDao
    abstract fun library(): LibraryDao
    abstract fun lookup(): LookupDao
    abstract fun dictionary(): DictionaryDao
    abstract fun conversations(): ConversationDao
    abstract fun sentenceTranslations(): SentenceTranslationDao
    abstract fun collections(): CollectionDao
    abstract fun termRenames(): TermRenameDao
    abstract fun leechHints(): LeechHintDao

    companion object {
        fun create(context: Context): AlbionDatabase =
            Room.databaseBuilder(context, AlbionDatabase::class.java, "albion.db").build()
    }
}
