package com.marat.albion.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.marat.albion.data.content.ContentImporter
import com.marat.albion.data.content.Deck
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Озвучка карточек: mp3 из assets/content/audio через Media3 ExoPlayer.
 * Если файла нет (или материалы не вшиты), произносит текст системным TTS (en-GB).
 * Вызывать с главного потока.
 */
class AudioPlayer internal constructor(
    private val context: Context,
    private val createPlayer: (Context) -> ExoPlayer,
    private val createTts: (Context, TextToSpeech.OnInitListener) -> TextToSpeech,
) {
    constructor(context: Context) : this(
        context,
        { ctx ->
            ExoPlayer.Builder(ctx)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                    /* handleAudioFocus = */ true,
                ).build()
        },
        { ctx, listener -> TextToSpeech(ctx, listener) },
    )

    private val _playing = MutableStateFlow<String?>(null)
    /** Ключ звучащего сейчас фрагмента — чтобы подсветить нужную кнопку. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private var speed = 1f
    private var volume = 1f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var requestId = 0L
    private var mediaRequestId: Long? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsGeneration = 0L
    private data class SpeechRequest(val id: String, val text: String)
    private var speech: SpeechRequest? = null
    private var ttsQueued: SpeechRequest? = null
    /** Подсказку об отсутствии английского голоса показываем один раз за запуск. */
    private var noVoiceShown = false

    fun setSpeed(value: Float) {
        speed = value
    }

    /** Громкость озвучки 0…1 относительно системной громкости медиа. */
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player?.volume = volume
    }

    /**
     * Проигрывает файл [file] колоды [deck]; при неудаче — TTS для [text].
     * [rate] — множитель к скорости из настроек (0.7 — медленный повтор в шэдоуинге).
     */
    fun play(deck: Deck, file: String?, text: String, key: String = "${deck.id}:$text", rate: Float = 1f) {
        if (file == null) {
            speak(text, key); return
        }
        val path = "${ContentImporter.AUDIO}/${deck.audioDir}/$file"
        val uri = Uri.parse("asset:///" + Uri.encode(path, "/"))
        playMedia(uri, key, speed * rate, volume, fallback = { speak(text, key) })
    }

    /** Задержка появления карточки не должна запускать озвучку после stop или ручного запуска. */
    suspend fun playDelayed(deck: Deck, file: String?, text: String, key: String, delayMs: Long) {
        val current = requestId
        delay(delayMs)
        if (requestId == current) play(deck, file, text, key)
    }

    /** Проигрывает собственную запись пользователя (шэдоуинг) — всегда в обычном темпе. */
    fun playFile(file: java.io.File, key: String) {
        if (!file.exists()) return
        playMedia(Uri.fromFile(file), key, 1f, 1f)
    }

    /** «Диктор → я»: остановка или другая озвучка отменяют отложенный второй фрагмент. */
    suspend fun playFileAfterCurrent(file: java.io.File, key: String) {
        if (_playing.value == null) return
        val current = requestId
        playing.first { it == null }
        delay(250)
        if (requestId == current) playFile(file, key)
    }

    private fun playMedia(uri: Uri, key: String, rate: Float, mediaVolume: Float, fallback: (() -> Unit)? = null) {
        // Сначала останавливаем прежнюю запись; её STATE_IDLE не относится к новой.
        stop()
        val engine = player ?: createPlayer(context).also { player = it }
        val id = ++requestId
        mediaRequestId = id
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (mediaRequestId != id) return
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    _playing.value = null
                    if (state == Player.STATE_ENDED) mediaRequestId = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (mediaRequestId != id) return
                stop()
                fallback?.invoke()
            }
        }
        playerListener = listener
        engine.addListener(listener)
        _playing.value = key
        engine.setMediaItem(MediaItem.fromUri(uri))
        engine.setPlaybackSpeed(rate)
        engine.volume = mediaVolume
        engine.prepare()
        engine.play()
    }

    fun speak(text: String, key: String = text) {
        if (text.isBlank()) return
        stop()
        val request = SpeechRequest("albion-${++requestId}", text)
        speech = request
        _playing.value = key
        val engine = tts
        if (engine == null) {
            ttsQueued = request
            val generation = ++ttsGeneration
            tts = createTts(context.applicationContext, TextToSpeech.OnInitListener { status ->
                // Даже немедленный ответ фабрики обрабатывается после присваивания tts.
                mainHandler.post { onTtsInitialized(generation, status) }
            })
        } else if (ttsReady) {
            speakNow(request)
        } else {
            ttsQueued = request
        }
    }

    private fun onTtsInitialized(generation: Long, status: Int) {
        if (ttsGeneration != generation) return
        val engine = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) {
            speech = null
            ttsQueued = null
            // Ошибка старого TTS не должна гасить уже запущенный MP3.
            if (mediaRequestId == null) _playing.value = null
            engine.shutdown()
            tts = null
            return
        }
        // Британский голос, затем любой английский. Без английского голоса не читаем
        // слова голосом по умолчанию (на русском телефоне это русский голос).
        val english = ENGLISH_LOCALES.firstOrNull { locale ->
            runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }
        if (english == null) {
            ttsReady = false
            speech = null
            ttsQueued = null
            if (mediaRequestId == null) _playing.value = null
            engine.shutdown()
            tts = null
            notifyNoEnglishVoice()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = speechFinished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = speechFinished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = speechFinished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = speechFinished(utteranceId)
        })
        val queued = ttsQueued
        ttsQueued = null
        if (queued != null && speech == queued) speakNow(queued)
    }

    private fun notifyNoEnglishVoice() {
        if (noVoiceShown) return
        noVoiceShown = true
        Toast.makeText(
            context.applicationContext,
            "Нет английского голоса для озвучки. Установите его: Настройки → Язык и ввод → Синтез речи",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun speechFinished(id: String?) {
        // События TTS приходят из другого потока и могут относиться к прежней фразе.
        mainHandler.post {
            if (id != null && speech?.id == id) {
                speech = null
                _playing.value = null
            }
        }
    }

    private fun speakNow(request: SpeechRequest) {
        if (speech != request) return
        val engine = tts ?: return
        engine.setSpeechRate(speed * 0.95f)
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        if (engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, request.id) != TextToSpeech.SUCCESS) {
            speechFinished(request.id)
        }
    }

    fun stop() {
        // Команда отменяет и активную запись, и ещё ожидающую инициализации озвучку.
        requestId++
        mediaRequestId = null
        speech = null
        ttsQueued = null
        _playing.value = null
        player?.let { engine ->
            playerListener?.let(engine::removeListener)
            playerListener = null
            engine.stop()
        }
        tts?.stop()
    }

    fun release() {
        stop()
        player?.release()
        player = null
        ttsGeneration++
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private companion object {
        val ENGLISH_LOCALES = listOf(Locale.UK, Locale.US, Locale.ENGLISH)
    }
}
