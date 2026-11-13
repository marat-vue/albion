package com.marat.albion.ui.conversation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marat.albion.AppContainer
import com.marat.albion.audio.VoiceRecorder
import com.marat.albion.data.conversation.ConversationProtocol
import com.marat.albion.data.db.ConversationMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Один разговор: отправка, потоковый ответ, разбор ошибок, подсказка, перевод и голосовой ввод.
 * Модель работает последовательно, поэтому сначала ответ (его ждут), потом проверка реплики.
 */
class ChatViewModel(private val c: AppContainer, context: Context, val id: Long) : ViewModel() {
    private val repo = c.conversations
    val recorder = VoiceRecorder(context)
    val conversation = repo.conversation(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val messages = repo.messages(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Текст ответа, пока модель его пишет; "" — ответ ещё не начался. null — модель свободна. */
    var streaming by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var draft by mutableStateOf(""); private set
    /** Черновик продиктован — отправится с пометкой голоса. */
    var draftVoice by mutableStateOf(false); private set
    var transcribing by mutableStateOf(false); private set
    var hint by mutableStateOf<String?>(null); private set
    var hintLoading by mutableStateOf(false); private set
    /** Модель пересказывает раннюю часть беседы. */
    var compacting by mutableStateOf(false); private set
    val checking = mutableStateMapOf<Long, Boolean>()
    private var replyJob: Job? = null
    private var hintJob: Job? = null

    val busy get() = streaming != null

    fun edit(text: String) {
        draft = text
        if (text.isBlank()) draftVoice = false
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        val voice = draftVoice
        draft = ""
        draftVoice = false
        hint = null
        hintJob?.cancel()
        error = null
        viewModelScope.launch {
            val messageId = try { repo.send(id, text, voice) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                draft = text
                error = ConversationProtocol.errorMessage(e)
                return@launch
            }
            respond(checkAfter = messageId)
        }
    }

    /** Ответить на последнюю реплику ученика (после ошибки или если экран закрыли во время ответа). */
    fun retry() {
        if (busy) return
        error = null
        respond(checkAfter = messages.value?.lastOrNull()?.takeIf { it.fromUser && it.correction == null }?.id)
    }

    /** Другой вариант последнего ответа. */
    fun regenerate() {
        if (busy) return
        error = null
        viewModelScope.launch {
            repo.dropLastReply(id)
            respond(checkAfter = null)
        }
    }

    private fun respond(checkAfter: Long?) {
        replyJob?.cancel()
        streaming = ""
        replyJob = viewModelScope.launch {
            try {
                repo.reply(id).collect { streaming = it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = ConversationProtocol.errorMessage(e)
            } finally {
                streaming = null
            }
            if (checkAfter != null) check(checkAfter)
            // Очередь модели справедлива: пересказ начнётся после разбора реплики.
            compact()
        }
    }

    private fun compact() {
        if (compacting) return
        compacting = true
        viewModelScope.launch {
            try { repo.compactIfNeeded(id) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Не вышло — история пока обрезается по-старому, попробуем после следующего ответа.
            } finally { compacting = false }
        }
    }

    fun stop() {
        replyJob?.cancel()
        streaming = null
    }

    fun check(messageId: Long) {
        if (checking[messageId] == true) return
        checking[messageId] = true
        viewModelScope.launch {
            try { repo.check(messageId) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Разбор — дополнение к беседе: при сбое реплика просто останется непроверенной.
            } finally { checking.remove(messageId) }
        }
    }

    fun requestHint() {
        if (busy || hintLoading) return
        hintLoading = true
        hint = null
        hintJob = viewModelScope.launch {
            try { repo.hint(id).collect { hint = it } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = ConversationProtocol.errorMessage(e)
            } finally { hintLoading = false }
        }
    }

    fun useHint() {
        val text = hint ?: return
        draft = if (draft.isBlank()) text else draft.trimEnd() + " " + text
        hint = null
    }

    fun dismissHint() { hintJob?.cancel(); hint = null; hintLoading = false }
    fun dismissError() { error = null }

    /** Запись до паузы в 2,5 с (ученик подбирает слова) или до минуты; затем Whisper. */
    fun startRecording(): Boolean {
        if (transcribing) return false
        c.audio.stop()
        error = null
        return recorder.start(null, maxMs = 60_000, silenceMs = 2_500) { transcribe() }
    }

    fun stopRecording() = recorder.stop()

    private fun transcribe() {
        val pcm = recorder.samples ?: return
        transcribing = true
        viewModelScope.launch {
            try {
                // Последние реплики подсказывают Whisper имена и слова темы.
                val context = messages.value.orEmpty().takeLast(2).joinToString(" ") { it.text }
                val text = c.speech.transcribe(pcm, context)
                if (text.isBlank()) error = "Не расслышал — попробуйте ещё раз, чуть громче."
                else {
                    draft = if (draft.isBlank()) text else draft.trimEnd() + " " + text
                    draftVoice = true
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = e.message?.takeIf { it.any { ch -> ch in 'А'..'я' } } ?: "Не удалось распознать речь. Попробуйте ещё раз."
            } finally { transcribing = false }
        }
    }

    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(id); onDone() }

    override fun onCleared() {
        recorder.clear()
        c.audio.stop()
    }
}
