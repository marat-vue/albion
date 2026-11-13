package com.marat.albion

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.marat.albion.audio.AudioPlayer
import com.marat.albion.data.content.ContentImporter
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.grammar.GrammarRepository
import com.marat.albion.data.repo.StudyRepository
import com.marat.albion.data.settings.SettingsRepository
import com.marat.albion.notify.Reminders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Простой контейнер зависимостей без DI-фреймворка. */
class AppContainer(context: Context) {
    // Фоновая работа никогда не должна ронять приложение — ошибки только в лог.
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("Albion", "Фоновая задача", e) }
    )
    val json = Json { ignoreUnknownKeys = true }
    val database = AlbionDatabase.create(context)
    val settings = SettingsRepository(context)
    val importer = ContentImporter(context.assets, database, settings, json)
    val study = StudyRepository(database, settings, json)
    val collections = com.marat.albion.data.repo.CollectionRepository(database)
    val grammar = GrammarRepository(context.assets, database)
    val audio = AudioPlayer(context)
    val tuner = com.marat.albion.data.repo.FsrsTuner(study, appScope)
    val dictionary = com.marat.albion.data.library.Dictionary(context.assets, database, settings, json)
    val library = com.marat.albion.data.library.LibraryRepository(context, database, json, dictionary)
    val bookWords = com.marat.albion.data.library.BookWordsRepository(database, json)
    val ai = com.marat.albion.data.ai.AiSettings(context, appScope)
    val groq = com.marat.albion.data.ai.GroqClient(ai)
    val translator = com.marat.albion.data.library.Translator(groq)
    val bookTranslator = com.marat.albion.data.library.BookTranslator(appScope)
    val speech = com.marat.albion.data.speech.SpeechToText(context, appScope)
    val conversations = com.marat.albion.data.conversation.ConversationRepository(database, groq, translator)
    val leechHints = com.marat.albion.data.repo.LeechHints.groq(database.leechHints(), groq)
}

class AlbionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.importer.importIfNeeded() }
        container.appScope.launch { container.library.syncBundled() }
        container.appScope.launch {
            try { container.dictionary.ensureLoaded() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("Albion", "Словарь не загружен", e) }
        }
        // Встроенной Qwen3.5 больше нет: освобождаем место, которое занимали её файлы.
        container.appScope.launch(Dispatchers.IO) { com.marat.albion.data.ai.removeLegacyOnDeviceModel(this@AlbionApp) }
        // Восстановить состояние загрузки Whisper, не загружая веса в память на старте.
        // Языковая модель ML Kit для книг загружается только по кнопке в «Чтении».
        container.speech.preload()
        Reminders.createChannel(this)
        // Будильник напоминания всегда соответствует настройкам.
        container.appScope.launch {
            container.settings.settings
                .map { Triple(it.reminderEnabled, it.reminderHour, it.reminderMinute) }
                .distinctUntilChanged()
                .collect { (enabled, hour, minute) ->
                    if (enabled) Reminders.schedule(this@AlbionApp, hour, minute) else Reminders.cancel(this@AlbionApp)
                }
        }
        // Скорость и громкость озвучки применяются к плееру везде, где он звучит.
        container.appScope.launch(Dispatchers.Main) {
            container.settings.settings
                .map { it.playbackSpeed to it.playbackVolume }
                .distinctUntilChanged()
                .collect { (speed, volume) ->
                    container.audio.setSpeed(speed)
                    container.audio.setVolume(volume)
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            container.speech.releaseMemory()
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer не задан") }
