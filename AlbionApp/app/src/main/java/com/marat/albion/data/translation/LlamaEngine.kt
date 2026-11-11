package com.marat.albion.data.translation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Keep
object LlamaBridge {
    @Keep fun interface TokenCallback { fun onTokens(utf8: ByteArray): Boolean }
    private val loadResult by lazy { runCatching { System.loadLibrary("albion_llm") } }
    fun requireAvailable() {
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }) {
            "Для Qwen3.5 нужен 64-битный Android (arm64 или x86_64). Словарь доступен."
        }
        check(loadResult.isSuccess) { "Не удалось загрузить движок перевода. Установите APK для своего устройства." }
    }
    external fun nativeLoad(path: String, threads: Int): Long
    external fun nativeGenerate(handle: Long, request: Long, slot: Int, prompt: ByteArray, maxTokens: Int,
                                temperature: Float, seed: Int, allowTruncate: Boolean, callback: TokenCallback): ByteArray
    external fun nativeCancel(handle: Long, request: Long)
    external fun nativeUnload(handle: Long)
}

/**
 * How to sample one request. [slot] [CHAT] keeps its state between calls: a prompt that extends the
 * previous one only evaluates the new tokens. Short tasks use [TASK] so they do not evict the chat.
 */
data class Generation(
    val slot: Int = TASK,
    val maxTokens: Int = 768,
    /** 0 — greedy, deterministic output for translations and corrections. */
    val temperature: Float = 0f,
    /** Return what was generated at [maxTokens] instead of failing. */
    val allowTruncate: Boolean = false,
    /** Keep weights in memory this long after the request. */
    val keepAliveMs: Long = 90_000,
) {
    companion object {
        const val CHAT = 0
        const val TASK = 1
    }
}

/** One native queue protects load/generate/free; request IDs isolate cancellation across callers. */
class LlamaEngine(private val context: Context, private val scope: CoroutineScope) {
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "albion-llama").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private var handle = 0L
    private var loaded: String? = null
    private var idle: Job? = null
    /** Когда выгрузить модель (мс, монотонные часы). Короткий запрос не сокращает срок, заданный длинным. */
    private var unloadAt = 0L
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun generate(file: File, prompt: String, options: Generation = Generation()): Flow<String> = channelFlow {
        mutex.withLock {
            idle?.cancel()
            try {
                LlamaBridge.requireAvailable()
                val identity = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                if (loaded != identity) {
                    _loading.value = true
                    withContext(dispatcher) {
                        releaseNative()
                        val memory = ActivityManager.MemoryInfo()
                        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
                        val needed = file.length() + 450L * 1024 * 1024
                        check(!memory.lowMemory && (memory.availMem == 0L || memory.availMem >= needed)) {
                            "Не хватает свободной памяти для модели. Закройте другие приложения или выберите Qwen3.5 0.8B."
                        }
                        GgufFile.requireQwen35(file)
                        handle = LlamaBridge.nativeLoad(file.absolutePath,
                            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4))
                        check(handle != 0L) { "Не удалось открыть модель перевода." }
                        loaded = identity
                    }
                    _loading.value = false
                }
                val request = sequence.incrementAndGet()
                val result = withContext(dispatcher) {
                    suspendCancellableCoroutine { continuation ->
                        val activeHandle = handle
                        continuation.invokeOnCancellation { LlamaBridge.nativeCancel(activeHandle, request) }
                        if (continuation.isActive) try {
                            val bytes = LlamaBridge.nativeGenerate(activeHandle, request, options.slot, prompt.toByteArray(Charsets.UTF_8),
                                options.maxTokens, options.temperature, (System.nanoTime() xor request).toInt(), options.allowTruncate) { utf8 ->
                                if (continuation.isActive) { trySend(utf8.toString(Charsets.UTF_8)); true } else false
                            }
                            if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
                send(result)
            } finally {
                _loading.value = false
                val now = System.nanoTime() / 1_000_000
                unloadAt = maxOf(unloadAt, now + options.keepAliveMs)
                val wait = unloadAt - now
                idle = scope.launch { delay(wait); unload() }
            }
        }
    }.buffer(Channel.CONFLATED).flowOn(Dispatchers.Default)

    private fun releaseNative() {
        if (handle != 0L) LlamaBridge.nativeUnload(handle)
        handle = 0
        loaded = null
        unloadAt = 0
    }
    suspend fun unload() = mutex.withLock { withContext(dispatcher) { releaseNative() } }
}
