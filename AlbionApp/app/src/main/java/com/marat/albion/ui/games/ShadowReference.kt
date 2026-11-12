package com.marat.albion.ui.games

import android.content.Context
import com.marat.albion.audio.AudioDecoder
import com.marat.albion.audio.AudioPlayer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.speech.SpokenWords

/**
 * Эталон для шэдоуинга: что звучит, как это проиграть и как получить PCM для сравнения
 * мелодики. Карточки озвучены отдельными mp3, фразы трека — отрезками длинной записи.
 */
interface ShadowReference {
    /** Стабильный ключ: состояние раунда, имя файла записи, ключи озвучки. */
    val id: String

    /** Есть ли запись диктора (иначе — системный TTS, медленный повтор недоступен). */
    val hasAudio: Boolean

    /** Слова, которые произносит диктор, — для проверки разборчивости. */
    fun expectedWords(): List<String>

    /** [rate] — множитель к скорости из настроек (0.7 — «Медленно»). */
    fun play(audio: AudioPlayer, key: String, rate: Float = 1f)

    /** Автозапуск при показе; отменяется, если пользователь успел что-то включить сам. */
    suspend fun playDelayed(audio: AudioPlayer, key: String, delayMs: Long)

    /** Моно 16 кГц или null, если записи нет. Вызывать не с главного потока. */
    fun decode(context: Context): FloatArray?
}

/** Озвучка карточки из assets/content/audio. */
class CardShadowReference(private val card: CardEntity) : ShadowReference {
    private val deck = Deck.byId(card.deck) ?: Deck.WORDS
    override val id: String get() = card.id
    override val hasAudio: Boolean get() = card.audio != null

    // Имя файла озвучки — ровно то, что произносит диктор («read (past).mp3» → «read»).
    override fun expectedWords(): List<String> =
        SpokenWords.split(card.audio?.substringBeforeLast('.')?.substringBefore(" (") ?: card.term).ifEmpty { SpokenWords.split(card.term) }

    override fun play(audio: AudioPlayer, key: String, rate: Float) = audio.play(deck, card.audio, card.term, key, rate)

    override suspend fun playDelayed(audio: AudioPlayer, key: String, delayMs: Long) =
        audio.playDelayed(deck, card.audio, card.term, key, delayMs)

    override fun decode(context: Context): FloatArray? = card.audio?.let { AudioDecoder.decodeCardAudio(context, deck, it) }
}
