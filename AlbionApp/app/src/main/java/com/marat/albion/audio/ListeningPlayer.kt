package com.marat.albion.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.marat.albion.data.listening.ListeningTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Повтор отрезка: A–B или текущая фраза. */
data class LoopRange(val startMs: Long, val endMs: Long, val phrase: Boolean = false)

data class ListeningState(
    val trackId: String? = null,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val loop: LoopRange? = null,
    /** Отмеченная точка A, пока не отмечена B. */
    val pendingA: Long? = null,
    val durationMs: Long = 0,
)

/**
 * Плеер «Аудио с текстом». Один ExoPlayer на приложение: его же отдаёт [ListeningService]
 * в MediaSession, поэтому запись продолжает звучать в фоне и управляется из уведомления.
 * Повтор A–B проверяется здесь, а не в экране, — он работает и при выключенном экране.
 * Вызывать с главного потока.
 */
class ListeningPlayer(
    private val context: Context,
    /** Сохранение места прослушивания (DataStore); вызывается на паузе и раз в несколько секунд. */
    private val saveProgress: suspend (trackId: String, positionMs: Long) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exo: ExoPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    val player: ExoPlayer get() = exo ?: create().also { exo = it }

    val positionMs: Long get() = exo?.currentPosition ?: 0L

    private fun create(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(playing = isPlaying) }
                    if (isPlaying) startTicker() else { ticker?.cancel(); persist() }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) _state.update { it.copy(durationMs = duration.coerceAtLeast(0)) }
                    // Отрезок до самого конца записи: повтор срабатывает и после окончания.
                    val loop = _state.value.loop
                    if (playbackState == Player.STATE_ENDED && loop != null) { seekTo(loop.startMs); play() }
                }
            })
        }

    /** Открывает трек, если он ещё не открыт; [startMs] — где остановились в прошлый раз. */
    fun load(track: ListeningTrack, startMs: Long) {
        if (_state.value.trackId == track.id) return
        persist()
        val meta = MediaMetadata.Builder()
            .setTitle(track.meta.title)
            .setArtist(listOf(track.meta.author, track.meta.reader).filter(String::isNotBlank).joinToString(" · "))
            .build()
        player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(track.audioUri).setMediaMetadata(meta).build(), startMs)
        player.prepare()
        _state.value = ListeningState(trackId = track.id, speed = _state.value.speed, durationMs = track.meta.durationMs)
        player.setPlaybackSpeed(_state.value.speed)
    }

    fun toggle() {
        val p = player
        if (p.isPlaying) p.pause()
        else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun pause() = exo?.pause()

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    /** Перемотка к фразе: если включён повтор фразы — он переезжает на неё. */
    fun seekToPhrase(startMs: Long, endMs: Long) {
        if (_state.value.loop?.phrase == true) _state.update { it.copy(loop = LoopRange(startMs, endMs, phrase = true)) }
        seekTo(startMs)
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        exo?.setPlaybackSpeed(speed)
    }

    /** Повтор фразы [startMs, endMs); повторный вызов выключает. */
    fun togglePhraseLoop(startMs: Long, endMs: Long) {
        _state.update {
            if (it.loop?.phrase == true) it.copy(loop = null) else it.copy(loop = LoopRange(startMs, endMs, phrase = true), pendingA = null)
        }
        restartTicker()
    }

    /** A–B: первое нажатие ставит A, второе — B и включает повтор, третье — сбрасывает. */
    fun markAB() {
        val now = positionMs
        _state.update {
            when {
                it.loop != null && !it.loop.phrase -> it.copy(loop = null, pendingA = null)
                it.pendingA == null -> it.copy(pendingA = now, loop = null)
                now > it.pendingA + MIN_LOOP_MS -> it.copy(loop = LoopRange(it.pendingA, now), pendingA = null)
                now < it.pendingA - MIN_LOOP_MS -> it.copy(loop = LoopRange(now, it.pendingA), pendingA = null)
                else -> it
            }
        }
        _state.value.loop?.let { seekTo(it.startMs) }
        restartTicker()
    }

    fun clearLoop() { _state.update { it.copy(loop = null, pendingA = null) }; restartTicker() }

    /** Частота опроса зависит от повтора — после его смены тикер перезапускается. */
    private fun restartTicker() { if (exo?.isPlaying == true) startTicker() }

    /** Продолжить после словаря; в конце записи заново не начинаем. */
    fun resume() { exo?.takeIf { !it.isPlaying && it.playbackState != Player.STATE_ENDED }?.play() }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceSave = 0L
            while (isActive) {
                val loop = _state.value.loop
                val p = exo ?: break
                // Конец фразы может выходить за запись (последняя строка LRC) — тогда повтор ловит STATE_ENDED.
                val end = loop?.endMs?.let { e -> if (p.duration > 0) minOf(e, p.duration) else e }
                if (loop != null && end != null && p.currentPosition >= end) p.seekTo(loop.startMs)
                // Точный опрос нужен только для повтора; иначе — редкие пробуждения ради сохранения места.
                val step = if (loop != null) TICK_MS else IDLE_TICK_MS
                delay(step)
                sinceSave += step
                if (sinceSave >= SAVE_EVERY_MS) { sinceSave = 0; persist() }
            }
        }
    }

    private fun persist() {
        val id = _state.value.trackId ?: return
        val pos = exo?.currentPosition ?: return
        scope.launch { saveProgress(id, pos) }
    }

    /** Остановка «насовсем» (закрыли уведомление или приложение без воспроизведения). */
    fun stop(saveProgress: Boolean = true) {
        if (saveProgress) persist()
        exo?.stop()
        _state.update { it.copy(trackId = null, playing = false, loop = null, pendingA = null) }
    }

    companion object {
        private const val TICK_MS = 50L
        private const val IDLE_TICK_MS = 1_000L
        private const val SAVE_EVERY_MS = 5_000L
        private const val MIN_LOOP_MS = 300L
    }
}
