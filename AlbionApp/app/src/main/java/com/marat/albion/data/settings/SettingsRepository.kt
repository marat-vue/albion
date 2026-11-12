package com.marat.albion.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "albion_settings",
    // Повреждённый файл (например, после отключения питания во время записи) заменяется настройками по умолчанию.
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная")
}

enum class NewCardOrder(val title: String) {
    LEVEL("По уровню CEFR"), FILE("Как в таблице"), RANDOM("Случайно")
}

data class Settings(
    val desiredRetention: Float = 0.9f,
    val newPerDay: Int = 20,
    val reviewsPerDay: Int = 200,
    val newOrder: NewCardOrder = NewCardOrder.LEVEL,
    val autoplayFront: Boolean = true,
    val autoplayBack: Boolean = false,
    val showIpaOnFront: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    /** Громкость озвучки 0…1. */
    val playbackVolume: Float = 1.0f,
    val grammarTextScale: Float = 1.0f,
    /** Час, с которого начинается новый учебный день (как в Anki). */
    val dayStartHour: Int = 4,
    val lastGrammarTopic: String? = null,
    /** Показывать русское значение на обороте карточек. */
    val showRussian: Boolean = true,
    val browseShowRussian: Boolean = true,
    /** Дневная цель — ответов в день по всем колодам. */
    val dailyGoal: Int = 30,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    /** Напоминать, только если цель ещё не выполнена или есть карточки к повторению. */
    val reminderOnlyIfNeeded: Boolean = true,
    /** Персональные параметры FSRS (21 число через запятую) или null — стандартные. */
    val fsrsParams: String? = null,
    val fsrsUpdatedAt: Long = 0,
    val fsrsReviews: Int = 0,
    val fsrsImprovement: Float = 0f,
    /** Масштаб текста в читалке. */
    val readerTextScale: Float = 1.0f,
    val readerLineSpacing: Float = 1.0f,
    val readerMargin: Int = 26,
    /** Подчёркивать в книгах слова из начатых карточек. */
    val readerMarkWords: Boolean = true,
    val shortSessionAnswers: Int = 10,
    val interfaceSounds: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
)

data class PracticeOptions(val deckId: String? = null, val count: Int = 10, val mode: Int = 0, val level: String = "")

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    /** Ошибка чтения с диска не должна ронять экраны: приложение работает с настройками по умолчанию. */
    private val data: Flow<Preferences> = store.data.catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }

    private object Keys {
        val retention = floatPreferencesKey("desired_retention")
        val newPerDay = intPreferencesKey("new_per_day")
        val reviewsPerDay = intPreferencesKey("reviews_per_day")
        val newOrder = stringPreferencesKey("new_order")
        val autoplayFront = booleanPreferencesKey("autoplay_front")
        val autoplayBack = booleanPreferencesKey("autoplay_back")
        val showIpaFront = booleanPreferencesKey("show_ipa_front")
        val speed = floatPreferencesKey("playback_speed")
        val volume = floatPreferencesKey("playback_volume")
        val grammarScale = floatPreferencesKey("grammar_text_scale")
        val dayStart = intPreferencesKey("day_start_hour")
        val lastTopic = stringPreferencesKey("last_grammar_topic")
        val contentVersion = stringPreferencesKey("content_version")
        val dictionaryVersion = intPreferencesKey("dictionary_version")
        val showRussian = booleanPreferencesKey("show_russian")
        val browseRussian = booleanPreferencesKey("browse_show_russian")
        val dailyGoal = intPreferencesKey("daily_goal")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val reminderOnlyIfNeeded = booleanPreferencesKey("reminder_only_if_needed")
        val fsrsParams = stringPreferencesKey("fsrs_params")
        val fsrsUpdatedAt = androidx.datastore.preferences.core.longPreferencesKey("fsrs_updated_at")
        val fsrsReviews = intPreferencesKey("fsrs_reviews")
        val fsrsImprovement = floatPreferencesKey("fsrs_improvement")
        val readerScale = floatPreferencesKey("reader_text_scale")
        val readerSpacing = floatPreferencesKey("reader_line_spacing")
        val readerMargin = intPreferencesKey("reader_margin")
        val readerMarkWords = booleanPreferencesKey("reader_mark_words")
        val viewedHelp = stringSetPreferencesKey("viewed_help")
        val shortSessionAnswers = intPreferencesKey("short_session_answers")
        val lastPractice = stringPreferencesKey("last_practice")
        val interfaceSounds = booleanPreferencesKey("interface_sounds")
        val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val themeMode = stringPreferencesKey("theme_mode_v2")
        val grammarOwnAnswers = stringPreferencesKey("grammar_own_answers")
        val wordOwnAnswers = stringPreferencesKey("word_own_answers")
    }

    val settings: Flow<Settings> = data.map { p ->
        Settings(
            desiredRetention = (p[Keys.retention] ?: 0.9f).coerceIn(0.7f, 0.99f),
            newPerDay = (p[Keys.newPerDay] ?: 20).coerceAtLeast(0),
            reviewsPerDay = (p[Keys.reviewsPerDay] ?: 200).coerceAtLeast(0),
            newOrder = p[Keys.newOrder]?.let { runCatching { NewCardOrder.valueOf(it) }.getOrNull() } ?: NewCardOrder.LEVEL,
            autoplayFront = p[Keys.autoplayFront] ?: true,
            autoplayBack = p[Keys.autoplayBack] ?: false,
            showIpaOnFront = p[Keys.showIpaFront] ?: true,
            playbackSpeed = (p[Keys.speed] ?: 1.0f).coerceIn(0.5f, 2f),
            playbackVolume = (p[Keys.volume] ?: 1.0f).coerceIn(0f, 1f),
            grammarTextScale = p[Keys.grammarScale] ?: 1.0f,
            dayStartHour = (p[Keys.dayStart] ?: 4).coerceIn(0, 23),
            lastGrammarTopic = p[Keys.lastTopic],
            showRussian = p[Keys.showRussian] ?: true,
            browseShowRussian = p[Keys.browseRussian] ?: true,
            dailyGoal = (p[Keys.dailyGoal] ?: 30).coerceAtLeast(1),
            reminderEnabled = p[Keys.reminderEnabled] ?: false,
            reminderHour = (p[Keys.reminderHour] ?: 19).coerceIn(0, 23),
            reminderMinute = (p[Keys.reminderMinute] ?: 0).coerceIn(0, 59),
            reminderOnlyIfNeeded = p[Keys.reminderOnlyIfNeeded] ?: true,
            fsrsParams = p[Keys.fsrsParams],
            fsrsUpdatedAt = p[Keys.fsrsUpdatedAt] ?: 0,
            fsrsReviews = p[Keys.fsrsReviews] ?: 0,
            fsrsImprovement = p[Keys.fsrsImprovement] ?: 0f,
            readerTextScale = p[Keys.readerScale] ?: 1.0f,
            readerLineSpacing = (p[Keys.readerSpacing] ?: 1.0f).coerceIn(0.85f, 1.3f),
            readerMargin = (p[Keys.readerMargin] ?: 26).coerceIn(16, 40),
            readerMarkWords = p[Keys.readerMarkWords] ?: true,
            interfaceSounds = p[Keys.interfaceSounds] ?: true,
            hapticsEnabled = p[Keys.hapticsEnabled] ?: true,
            reduceMotion = p[Keys.reduceMotion] ?: false,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            shortSessionAnswers = (p[Keys.shortSessionAnswers] ?: 10).takeIf { it in listOf(5, 10, 20) } ?: 10,
        )
    }.distinctUntilChanged()

    val viewedHelp: Flow<Set<String>> = data.map { it[Keys.viewedHelp].orEmpty() }

    val lastPractice: Flow<String?> = data.map { it[Keys.lastPractice] }

    /** Ответы «Мой ответ тоже верен» — кандидаты в альтернативы заданий грамматики. */
    val grammarOwnAnswers: Flow<List<com.marat.albion.data.grammar.OwnAnswer>> =
        data.map { com.marat.albion.data.grammar.OwnAnswerLog.parse(it[Keys.grammarOwnAnswers]) }.distinctUntilChanged()

    suspend fun addGrammarOwnAnswer(taskId: String, answer: String) = edit {
        it[Keys.grammarOwnAnswers] = com.marat.albion.data.grammar.OwnAnswerLog.append(it[Keys.grammarOwnAnswers], taskId, answer)
    }

    suspend fun clearGrammarOwnAnswers() = edit { it.remove(Keys.grammarOwnAnswers) }

    /** Принятые «Мой ответ тоже верен» варианты слов при вводе RU → EN (синонимы): id слова → ответ. */
    val wordOwnAnswers: Flow<List<com.marat.albion.data.grammar.OwnAnswer>> =
        data.map { com.marat.albion.data.grammar.OwnAnswerLog.parse(it[Keys.wordOwnAnswers]) }.distinctUntilChanged()

    suspend fun addWordOwnAnswer(cardId: String, answer: String) = edit {
        it[Keys.wordOwnAnswers] = com.marat.albion.data.grammar.OwnAnswerLog.append(it[Keys.wordOwnAnswers], cardId, answer)
    }

    fun practiceOptions(game: String, defaultCount: Int = 10): Flow<PracticeOptions> = data.map { p ->
        PracticeOptions(
            p[stringPreferencesKey("practice_${game}_deck")],
            p[intPreferencesKey("practice_${game}_count")] ?: defaultCount,
            p[intPreferencesKey("practice_${game}_mode")] ?: 0,
            p[stringPreferencesKey("practice_${game}_level")].orEmpty(),
        )
    }

    suspend fun savePracticeOptions(game: String, options: PracticeOptions, markAsLast: Boolean = true) = edit { p ->
        val deckKey = stringPreferencesKey("practice_${game}_deck")
        if (options.deckId == null) p.remove(deckKey) else p[deckKey] = options.deckId
        p[intPreferencesKey("practice_${game}_count")] = options.count
        p[intPreferencesKey("practice_${game}_mode")] = options.mode
        p[stringPreferencesKey("practice_${game}_level")] = options.level
        if (markAsLast) p[Keys.lastPractice] = game
    }

    suspend fun setShortSessionAnswers(count: Int) = edit { it[Keys.shortSessionAnswers] = count.takeIf { n -> n in listOf(5, 10, 20) } ?: 10 }

    suspend fun markHelpViewed(key: String) = edit { it[Keys.viewedHelp] = it[Keys.viewedHelp].orEmpty() + key }

    suspend fun current(): Settings = settings.first()

    suspend fun setRetention(v: Float) = edit { it[Keys.retention] = v }
    suspend fun setNewPerDay(v: Int) = edit { it[Keys.newPerDay] = v }
    suspend fun setReviewsPerDay(v: Int) = edit { it[Keys.reviewsPerDay] = v }
    suspend fun setNewOrder(v: NewCardOrder) = edit { it[Keys.newOrder] = v.name }
    suspend fun setAutoplayFront(v: Boolean) = edit { it[Keys.autoplayFront] = v }
    suspend fun setAutoplayBack(v: Boolean) = edit { it[Keys.autoplayBack] = v }
    suspend fun setShowIpaOnFront(v: Boolean) = edit { it[Keys.showIpaFront] = v }
    suspend fun setPlaybackSpeed(v: Float) = edit { it[Keys.speed] = v }
    suspend fun setPlaybackVolume(v: Float) = edit { it[Keys.volume] = v }
    suspend fun setGrammarTextScale(v: Float) = edit { it[Keys.grammarScale] = v }
    suspend fun setDayStartHour(v: Int) = edit { it[Keys.dayStart] = v }
    suspend fun setLastGrammarTopic(v: String) = edit { it[Keys.lastTopic] = v }
    suspend fun setInterfaceSounds(v: Boolean) = edit { it[Keys.interfaceSounds] = v }
    suspend fun setHapticsEnabled(v: Boolean) = edit { it[Keys.hapticsEnabled] = v }
    suspend fun setReduceMotion(v: Boolean) = edit { it[Keys.reduceMotion] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[Keys.themeMode] = v.name }
    suspend fun setShowRussian(v: Boolean) = edit { it[Keys.showRussian] = v }
    suspend fun setBrowseShowRussian(v: Boolean) = edit { it[Keys.browseRussian] = v }
    suspend fun setDailyGoal(v: Int) = edit { it[Keys.dailyGoal] = v }
    suspend fun setReminderEnabled(v: Boolean) = edit { it[Keys.reminderEnabled] = v }
    suspend fun setReminderTime(hour: Int, minute: Int) = edit { it[Keys.reminderHour] = hour; it[Keys.reminderMinute] = minute }
    suspend fun setReminderOnlyIfNeeded(v: Boolean) = edit { it[Keys.reminderOnlyIfNeeded] = v }

    suspend fun setReaderTextScale(v: Float) = edit { it[Keys.readerScale] = v }
    suspend fun setReaderLineSpacing(v: Float) = edit { it[Keys.readerSpacing] = v.coerceIn(0.85f, 1.3f) }
    suspend fun setReaderMargin(v: Int) = edit { it[Keys.readerMargin] = v.coerceIn(16, 40) }
    suspend fun setReaderMarkWords(v: Boolean) = edit { it[Keys.readerMarkWords] = v }

    suspend fun setFsrsParams(params: String?, reviews: Int, improvement: Float) = edit {
        if (params == null) {
            it.remove(Keys.fsrsParams); it.remove(Keys.fsrsUpdatedAt); it.remove(Keys.fsrsReviews); it.remove(Keys.fsrsImprovement)
        } else {
            it[Keys.fsrsParams] = params
            it[Keys.fsrsUpdatedAt] = System.currentTimeMillis()
            it[Keys.fsrsReviews] = reviews
            it[Keys.fsrsImprovement] = improvement
        }
    }

    /** Не перезаписываем параметры, сменившиеся за время фонового подбора. */
    suspend fun setFsrsParamsIfUnchanged(
        expectedParams: String?, expectedUpdatedAt: Long,
        params: String, reviews: Int, improvement: Float,
    ): Boolean {
        var applied = false
        edit {
            if (it[Keys.fsrsParams] == expectedParams && (it[Keys.fsrsUpdatedAt] ?: 0L) == expectedUpdatedAt) {
                it[Keys.fsrsParams] = params
                it[Keys.fsrsUpdatedAt] = System.currentTimeMillis()
                it[Keys.fsrsReviews] = reviews
                it[Keys.fsrsImprovement] = improvement
                applied = true
            }
        }
        return applied
    }

    suspend fun contentVersion(): String? = data.first()[Keys.contentVersion]
    suspend fun setContentVersion(v: String) = edit { it[Keys.contentVersion] = v }
    suspend fun dictionaryVersion(): Int = data.first()[Keys.dictionaryVersion] ?: 0
    suspend fun setDictionaryVersion(v: Int) = edit { it[Keys.dictionaryVersion] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }
}
