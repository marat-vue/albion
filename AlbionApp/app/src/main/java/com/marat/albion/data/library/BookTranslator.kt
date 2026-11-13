package com.marat.albion.data.library

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

enum class BookTranslationPhase { Checking, NotInstalled, Downloading, Ready, Failed }

data class BookTranslationStatus(
    val phase: BookTranslationPhase = BookTranslationPhase.Checking,
    val message: String? = null,
    /** Загрузка ждёт Wi-Fi: мобильная сеть не разрешена. */
    val wifiOnly: Boolean = false,
) {
    val ready get() = phase == BookTranslationPhase.Ready
    val summary get() = when (phase) {
        BookTranslationPhase.Checking -> "Проверяю офлайн-перевод…"
        BookTranslationPhase.NotInstalled -> "Не установлен · около 30 МБ"
        BookTranslationPhase.Downloading -> if (wifiOnly) "Загружаю · по Wi-Fi" else "Загружаю языковую модель…"
        BookTranslationPhase.Ready -> "Английский → русский · работает офлайн"
        BookTranslationPhase.Failed -> "Не удалось загрузить"
    }
}

/**
 * Kept separate from the AI conversation partner: reading works offline and never needs Groq.
 * The ML Kit model (~30 MB) is downloaded only when the user asks; until then the reader uses the dictionary.
 */
class BookTranslator internal constructor(
    private val scope: CoroutineScope,
    private val backend: BookTranslationBackend,
) {
    constructor(scope: CoroutineScope) : this(scope, MlKitBookTranslationBackend())

    private val lock = Mutex()
    private var download: Job? = null
    private val translation = Mutex()
    private val _state = MutableStateFlow(BookTranslationStatus())
    val state = _state.asStateFlow()
    private val cache = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    /** Checks the on-device model without touching the network. */
    fun refresh() {
        scope.launch { installed() }
    }

    /** ML Kit keeps downloaded language models across app restarts and updates. */
    private suspend fun installed(): Boolean = lock.withLock {
        when (_state.value.phase) {
            BookTranslationPhase.Ready -> return@withLock true
            BookTranslationPhase.Downloading -> return@withLock false
            else -> Unit
        }
        val downloaded = try { backend.isDownloaded() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (downloaded) _state.value = BookTranslationStatus(BookTranslationPhase.Ready)
        else if (_state.value.phase == BookTranslationPhase.Checking) _state.value = BookTranslationStatus(BookTranslationPhase.NotInstalled)
        downloaded
    }

    /** Explicit user action. Without [allowMobile] ML Kit waits for Wi-Fi. */
    fun install(allowMobile: Boolean) {
        download = scope.launch {
            lock.withLock {
                val phase = _state.value.phase
                if (phase == BookTranslationPhase.Ready || phase == BookTranslationPhase.Downloading) return@launch
                _state.value = BookTranslationStatus(BookTranslationPhase.Downloading, wifiOnly = !allowMobile)
            }
            try {
                backend.download(allowMobile)
                _state.value = BookTranslationStatus(BookTranslationPhase.Ready)
            } catch (cancelled: CancellationException) {
                _state.value = BookTranslationStatus(BookTranslationPhase.NotInstalled)
                throw cancelled
            } catch (_: Exception) {
                _state.value = BookTranslationStatus(BookTranslationPhase.Failed,
                    "Не удалось загрузить языковую модель. Проверьте подключение к интернету и свободное место, затем повторите попытку.")
            }
        }
    }

    /** Stops waiting for Wi-Fi so the user can choose another network option. */
    fun cancelInstall() {
        download?.cancel()
    }

    suspend fun translate(text: String): String {
        val selected = text.trim()
        require(selected.isNotEmpty()) { "Выберите слово или фрагмент для перевода." }
        require(selected.length <= MAX_SELECTION) { "Выделите более короткий фрагмент: до $MAX_SELECTION символов." }
        check(installed()) {
            if (_state.value.phase == BookTranslationPhase.Downloading) "Языковая модель ещё загружается. Пока работает словарь."
            else "Автоперевод не установлен. Пока работает словарь."
        }
        return translation.withLock {
            cache[selected]?.let { return@withLock it }
            val result = try { backend.translate(selected).trim() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throw IllegalStateException("Не удалось перевести фрагмент. Попробуйте ещё раз или выберите более короткий текст.", error)
            }
            check(result.isNotEmpty()) { "Перевод получился пустым. Попробуйте другой фрагмент." }
            check(!selected.any { it in 'a'..'z' || it in 'A'..'Z' } || !result.equals(selected, ignoreCase = true)) {
                "Для этого фрагмента нет автоперевода. Выберите словарное значение или укажите свой перевод."
            }
            cache[selected] = result
            result
        }
    }

    companion object {
        const val MAX_SELECTION = 1_800
    }
}

internal interface BookTranslationBackend {
    suspend fun isDownloaded(): Boolean
    suspend fun download(allowMobile: Boolean)
    suspend fun translate(text: String): String
}

private class MlKitBookTranslationBackend : BookTranslationBackend {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.RUSSIAN)
        .build()

    override suspend fun isDownloaded(): Boolean {
        val models = RemoteModelManager.getInstance()
        return listOf(TranslateLanguage.ENGLISH, TranslateLanguage.RUSSIAN).all {
            models.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
    }

    // Clients are closed after every operation; only ML Kit's on-disk model is retained.
    override suspend fun download(allowMobile: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (!allowMobile) requireWifi() }.build()
        Translation.getClient(options).use { client -> client.downloadModelIfNeeded(conditions).await() }
    }

    override suspend fun translate(text: String): String = Translation.getClient(options).use { client ->
        client.translate(text).await()
    }
}
