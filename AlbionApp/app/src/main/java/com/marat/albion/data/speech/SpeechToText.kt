package com.marat.albion.data.speech

import android.content.Context
import androidx.annotation.Keep
import com.marat.albion.data.translation.LlamaBridge
import com.marat.albion.data.translation.ModelCatalog
import com.marat.albion.data.translation.TranslationModel
import com.marat.albion.data.translation.TranslationModels
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** whisper.cpp lives in the same native library as llama.cpp. */
@Keep
object WhisperBridge {
    external fun nativeLoad(path: String): Long
    /** [words] — one token per line with timing and confidence (see albion_stt.cpp) instead of plain text. */
    external fun nativeTranscribe(handle: Long, request: Long, pcm: FloatArray, threads: Int, prompt: String?, words: Boolean): ByteArray
    external fun nativeCancel(handle: Long, request: Long)
    external fun nativeUnload(handle: Long)
}

private const val WHISPER_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
private fun whisper(id: String, title: String, fileName: String, bytes: Long, sha256: String) = TranslationModel(
    id, title, fileName, bytes, "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_REVISION/$fileName", sha256)

/** English-only checkpoints: for an English learner they are more accurate than multilingual ones of the same size. */
val WHISPER_MODELS = listOf(
    whisper("base.en", "Whisper Base · 60 МБ", "ggml-base.en-q5_1.bin", 59_721_011,
        "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
    whisper("small.en", "Whisper Small · 190 МБ", "ggml-small.en-q5_1.bin", 190_098_681,
        "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
)

/** Bounded header check: reject a wrong file before whisper.cpp allocates memory for it. */
fun requireWhisperModel(file: File) {
    RandomAccessFile(file, "r").use { input ->
        require(input.length() > 1_000_000 && Integer.reverseBytes(input.readInt()) == 0x67676d6c) {
            "Нужна модель Whisper в формате ggml (.bin)."
        }
    }
}

val WHISPER_CATALOG = ModelCatalog(
    key = "whisper_speech", directory = "speech-models", options = WHISPER_MODELS, roomy = "small.en", compact = "base.en",
    customTitle = null, purpose = "Офлайн-распознавание речи для разговорной практики",
    readySummary = "распознаёт речь офлайн", missingSummary = "Модель не установлена · от 60 МБ",
    unavailableSummary = "Голосовой ввод недоступен на этом устройстве",
    validate = ::requireWhisperModel, requireRuntime = LlamaBridge::requireAvailable,
)

/**
 * Offline English speech-to-text with Whisper. Audio is 16 kHz mono in −1…1 and never leaves the device.
 * The model stays loaded for a few minutes so consecutive voice messages start instantly.
 */
class SpeechToText(context: Context, private val scope: CoroutineScope) {
    val models = TranslationModels(context.applicationContext, scope, WHISPER_CATALOG)
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "albion-whisper").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private var handle = 0L
    private var loaded: String? = null
    private var idle: Job? = null

    fun preload() { scope.launch { models.refresh() } }

    /** [hint] — recent conversation text: Whisper spells names and topic words the same way. */
    suspend fun transcribe(pcm: FloatArray, hint: String = ""): String = clean(recognize(pcm, hint, words = false))

    /**
     * Words with timing and confidence, for checking pronunciation. No hint by default:
     * knowing the expected phrase, Whisper would "hear" it in an unclear recording. A forced choice
     * (minimal pairs) passes both candidates so neither is favoured.
     */
    suspend fun words(pcm: FloatArray, hint: String = ""): List<HeardWord> = SpokenWords.parse(recognize(pcm, hint, words = true))

    private suspend fun recognize(pcm: FloatArray, hint: String, words: Boolean): String = mutex.withLock {
        idle?.cancel()
        try {
            LlamaBridge.requireAvailable()
            val (file, identity) = models.activeModel() ?: error("Установите модель распознавания речи.")
            require(pcm.size >= 16_000 / 2) { "Запись слишком короткая. Скажите фразу целиком." }
            if (loaded != identity) withContext(dispatcher) {
                release()
                handle = WhisperBridge.nativeLoad(file.absolutePath)
                check(handle != 0L) { "Не удалось открыть модель распознавания речи." }
                loaded = identity
            }
            // Whisper pads every request to 30 s; longer speech is split into windows.
            val audio = if (pcm.size < 16_000) pcm.copyOf(16_000) else pcm
            val request = sequence.incrementAndGet()
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
            val raw = withContext(dispatcher) {
                suspendCancellableCoroutine { continuation ->
                    val activeHandle = handle
                    continuation.invokeOnCancellation { WhisperBridge.nativeCancel(activeHandle, request) }
                    try {
                        val bytes = WhisperBridge.nativeTranscribe(activeHandle, request, audio, threads, hint.takeLast(400).ifBlank { null }, words)
                        continuation.resume(bytes.toString(Charsets.UTF_8))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            raw
        } finally {
            idle = scope.launch { delay(180_000); unload() }
        }
    }

    private fun release() {
        if (handle != 0L) WhisperBridge.nativeUnload(handle)
        handle = 0
        loaded = null
    }
    suspend fun unload() = mutex.withLock { withContext(dispatcher) { release() } }
    fun releaseMemory() { scope.launch { unload() } }

    companion object {
        /** Whisper marks silence and noise as "[BLANK_AUDIO]", "(wind blowing)" and the like. */
        fun clean(raw: String): String = raw
            .replace(Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
