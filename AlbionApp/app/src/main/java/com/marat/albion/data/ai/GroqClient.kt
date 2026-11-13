package com.marat.albion.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подключение к Groq. [builtIn] — работает ключ, вшитый при сборке. [restUntil] — модели, отдыхающие после
 * исчерпанного лимита: id → до какого момента (мс).
 */
data class AiStatus(
    val hasKey: Boolean = BUILT_IN_KEY.isNotEmpty(),
    val builtIn: Boolean = BUILT_IN_KEY.isNotEmpty(),
    val restUntil: Map<String, Long> = emptyMap(),
) {
    val ready get() = hasKey
    /** Модель, которая ответит сейчас; null — лимиты всех моделей исчерпаны. */
    fun model(now: Long = System.currentTimeMillis()) = ModelLadder.pick(restUntil, now)
    val summary get() = if (!hasKey) "Нужен бесплатный ключ Groq"
        else model()?.let { "${it.title} · Groq" } ?: "Лимиты Groq исчерпаны"
}

/** Ключ из local.properties (groq.apiKey), вшитый при сборке; пустой — ключ вводится в приложении. */
private val BUILT_IN_KEY: String = com.marat.albion.BuildConfig.GROQ_API_KEY.trim()

/**
 * Ключ Groq и паузы моделей. Свой ключ лежит в личных настройках приложения и исключён из резервных копий
 * (см. `backup_rules.xml`); без него используется ключ, вшитый при сборке. Паузы переживают перезапуск,
 * чтобы после исчерпанного дневного лимита не стучаться в ту же модель. Настройки читаются вне главного потока.
 */
class AiSettings(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _state = MutableStateFlow(AiStatus())
    val state = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val own = !prefs.getString(KEY, null).isNullOrBlank()
            val now = System.currentTimeMillis()
            val rest = GroqModel.entries.associate { it.id to prefs.getLong(REST + it.id, 0L) }.filterValues { it > now }
            _state.value = AiStatus(own || BUILT_IN_KEY.isNotEmpty(), !own && BUILT_IN_KEY.isNotEmpty(), rest)
        }
    }

    /** Читает файл настроек: вызывать вне главного потока. */
    fun key(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: BUILT_IN_KEY.ifEmpty { null }

    fun setKey(key: String) {
        prefs.edit().putString(KEY, key.trim()).apply()
        _state.update { it.copy(hasKey = key.isNotBlank() || BUILT_IN_KEY.isNotEmpty(), builtIn = key.isBlank() && BUILT_IN_KEY.isNotEmpty()) }
    }
    fun clearKey() {
        prefs.edit().remove(KEY).apply()
        _state.update { it.copy(hasKey = BUILT_IN_KEY.isNotEmpty(), builtIn = BUILT_IN_KEY.isNotEmpty()) }
    }

    /** Модель не трогаем до [until]; следующий запрос уйдёт к следующей по силе. */
    fun rest(model: GroqModel, until: Long) {
        prefs.edit().putLong(REST + model.id, until).apply()
        _state.update { it.copy(restUntil = it.restUntil + (model.id to until)) }
    }

    private companion object {
        const val PREFS = "groq"
        const val KEY = "apiKey"
        const val REST = "restUntil."
    }
}

/**
 * Запросы к Groq по OpenAI-совместимому API. Нужен интернет; из России — через VPN.
 * Каждый запрос идёт к самой сильной модели, у которой есть лимит; на 429 модель уходит на паузу,
 * сколько велел Groq, и тот же запрос повторяется на следующей. Когда пауза кончается, модель снова первая.
 */
class GroqClient(private val settings: AiSettings, private val clock: () -> Long = System::currentTimeMillis) {
    val status = settings.state

    /** Ответ по мере генерации: каждый элемент — весь текст на этот момент. */
    fun stream(messages: List<ChatMessage>, maxTokens: Int, temperature: Float = 0.7f): Flow<String> = flow {
        val connection = connect(messages, maxTokens, temperature, stream = true)
        val stop = disconnectOnCancel(connection)
        try {
            val text = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val piece = GroqProtocol.delta(line) ?: continue
                    text.append(piece)
                    emit(text.toString())
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw offline()
        } finally {
            stop.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Ответ целиком. temperature 0 — для разбора ошибок и перевода. */
    suspend fun complete(messages: List<ChatMessage>, maxTokens: Int, temperature: Float = 0f): String = withContext(Dispatchers.IO) {
        val connection = connect(messages, maxTokens, temperature, stream = false)
        val stop = disconnectOnCancel(connection)
        try {
            GroqProtocol.content(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (error: IOException) {
            ensureActive()
            throw offline()
        } finally {
            stop.dispose()
            connection.disconnect()
        }
    }

    /**
     * Открывает ответ модели, спускаясь по лестнице при исчерпанных лимитах. Возвращает соединение
     * с кодом 200; ошибки, не связанные с лимитом, сразу уходят наверх.
     */
    private suspend fun connect(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, stream: Boolean): HttpURLConnection {
        val key = savedKey()
        while (true) {
            val now = clock()
            val model = settings.state.value.model(now) ?: throw exhausted(now)
            val connection = open("chat/completions", key)
            val stop = disconnectOnCancel(connection)
            try {
                send(connection, GroqProtocol.request(model, messages, maxTokens, temperature, stream))
                val code = connection.responseCode
                if (code == 200) {
                    restIfDailyRequestsUsedUp(model, connection)
                    stop.dispose()
                    return connection
                }
                val error = GroqProtocol.error(code, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                    connection.getHeaderField("retry-after"))
                connection.disconnect()
                when (error) {
                    is RateLimitException -> settings.rest(model, clock() + error.waitMs)
                    is ModelUnavailableException -> settings.rest(model, clock() + UNAVAILABLE_REST_MS)
                    else -> throw error
                }
            } catch (error: IOException) {
                connection.disconnect()
                currentCoroutineContext().ensureActive()
                throw offline()
            } finally {
                stop.dispose()
            }
        }
    }

    /** Groq сообщает остаток дневных запросов в каждом ответе: последний — сразу пауза до сброса. */
    private fun restIfDailyRequestsUsedUp(model: GroqModel, connection: HttpURLConnection) {
        if (connection.getHeaderField("x-ratelimit-remaining-requests")?.trim()?.toLongOrNull() != 0L) return
        val reset = connection.getHeaderField("x-ratelimit-reset-requests")?.let(GroqProtocol::duration) ?: GroqProtocol.DEFAULT_WAIT_MS
        settings.rest(model, clock() + reset)
    }

    private fun exhausted(now: Long): AiException {
        val minutes = ((ModelLadder.nextFree(settings.state.value.restUntil, now) - now) / 60_000 + 1).coerceAtLeast(1)
        val wait = if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
        return RateLimitException("Лимиты всех моделей Groq исчерпаны. Собеседник вернётся примерно через $wait.", minutes * 60_000)
    }

    /** Проверка ключа и доступа (VPN) без траты токенов. [key] — новый ключ до сохранения, иначе сохранённый. */
    suspend fun verify(key: String? = null) = withContext(Dispatchers.IO) {
        val connection = open("models", key ?: savedKey(), post = false).apply { readTimeout = 15_000 }
        val stop = disconnectOnCancel(connection)
        try {
            val code = connection.responseCode
            if (code != 200) throw GroqProtocol.error(code, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: IOException) {
            ensureActive()
            throw offline()
        } finally {
            stop.dispose()
            connection.disconnect()
        }
    }

    private fun savedKey() = settings.key() ?: throw AiException("Добавьте ключ Groq в настройках собеседника.")

    private fun offline() = AiException("Нет связи с Groq. Проверьте интернет и включите VPN.")

    /**
     * Отмена прерывает блокирующее чтение. Обработчик вызывается в потоке, отменившем корутину
     * (часто главном), поэтому сокет закрывается в отдельном потоке.
     */
    private suspend fun disconnectOnCancel(connection: HttpURLConnection): DisposableHandle =
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) Thread { try { connection.disconnect() } catch (_: Exception) {} }.start()
        }

    private fun open(path: String, key: String, post: Boolean = true): HttpURLConnection =
        (URL("${GroqProtocol.BASE_URL}/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000
            requestMethod = if (post) "POST" else "GET"
            doOutput = post
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", if (post) "application/json, text/event-stream" else "application/json")
            if (post) setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

    private fun send(connection: HttpURLConnection, body: String) {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        /** Модель пропала из аккаунта или с Groq: проверим снова через сутки. */
        const val UNAVAILABLE_REST_MS = 24 * 3_600_000L
    }
}

/** Убирает следы встроенной Qwen3.5 прежних версий: файлы моделей (до 1,3 ГБ) и их настройки. Вызывать на IO. */
fun removeLegacyOnDeviceModel(context: Context) {
    try {
        val prefs = context.getSharedPreferences("groq_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacyRemoved", false)) return
        java.io.File(context.noBackupFilesDir, "translation-models").deleteRecursively()
        context.getExternalFilesDir(null)?.let { java.io.File(it, "translation-models").deleteRecursively() }
        context.deleteSharedPreferences("qwen_translation")
        prefs.edit().putBoolean("legacyRemoved", true).apply()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { /* Не удалось — попробуем при следующем запуске. */ }
}
