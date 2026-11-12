package com.marat.albion.ui.games

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.audio.VoiceRecorder
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.games.MinimalPairs
import com.marat.albion.data.games.PairSpeech
import com.marat.albion.data.games.PairTask
import com.marat.albion.data.games.PairWord
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.components.TextButton
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.theme.IpaStyle
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Проверка произношения слова пары через Whisper. */
private sealed interface PairVoice {
    data object Idle : PairVoice
    data object Checking : PairVoice
    data class Heard(val result: PairSpeech, val text: String) : PairVoice
    data class Failed(val message: String) : PairVoice
}

/**
 * Раунд «Минимальных пар»: услышать слово и выбрать его из пары, затем произнести самому.
 * Засчитывается слух; речь — подсказка: Whisper по одному слову не отличит долготу гласного надёжно.
 */
@Composable
fun MinimalPairRound(vm: GameViewModel, card: CardEntity) {
    val task = remember(card.id) { vm.pairTask(card) } ?: return
    val c = LocalAppContainer.current
    val playing by c.audio.playing.collectAsStateWithLifecycle()
    var picked by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    var shake by remember(card.id) { mutableIntStateOf(0) }
    val answered = picked != null
    val ok = picked == task.target.id
    val targetKey = "pair:${task.target.id}"

    fun play(word: PairWord, key: String = "pair:${word.id}", rate: Float = 1f) {
        val source = vm.pairCard(word.id)
        c.audio.play(Deck.byId(source?.deck ?: card.deck) ?: Deck.WORDS, source?.audio, word.term, key, rate)
    }

    LaunchedEffect(card.id) { c.audio.playDelayed(Deck.byId(card.deck) ?: Deck.WORDS, card.audio, card.term, targetKey, delayMs = 300) }

    fun choose(word: PairWord) {
        if (answered) return
        picked = word.id
        val right = word.id == task.target.id
        if (!right) shake++
        // На слух — узнавание, а не вспоминание значения: в FSRS не идёт.
        vm.record(right, if (right) null else Mistake(task.pair.contrast.label, word.term, task.target.term))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TaskCard(Modifier.shakeOn(shake), centered = true, label = if (answered) "Звуки ${task.pair.contrast.label}" else "Какое слово прозвучало?") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PracticeAudioButton(playing = playing == targetKey, onClick = { play(task.target, targetKey) }, size = 64.dp)
                    GhostButton("Медленнее", onClick = { play(task.target, targetKey, 0.7f) }, icon = AlbionIcons.SlowMotionVideo)
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                task.options.forEachIndexed { index, word ->
                    OptionButton(
                        text = if (answered) "${word.term}  ${word.ipa.substringBefore('·').trim()}" else word.term,
                        index = index,
                        state = when {
                            !answered -> OptionState.Idle
                            word.id == task.target.id -> OptionState.Correct
                            word.id == picked -> OptionState.Wrong
                            else -> OptionState.Dim
                        },
                        onClick = { choose(word) },
                    )
                }
            }
            AnimatedVisibility(answered, enter = fadeIn(tween(300)) + expandVertically()) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    NoteCard {
                        Eyebrow("Сравните")
                        Spacer(Modifier.height(8.dp))
                        task.options.forEach { word ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                PracticeAudioButton(playing = playing == "cmp:${word.id}", onClick = { play(word, "cmp:${word.id}") }, size = 40.dp, primary = false)
                                Spacer(Modifier.width(12.dp))
                                Text(word.term, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(8.dp))
                                Text(word.ipa.substringBefore('·').trim(), style = IpaStyle)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(task.pair.contrast.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    SpeakStep(task, card.id, onPlayTarget = { play(task.target, targetKey) })
                }
            }
        }
        AnimatedVisibility(answered, enter = fadeIn(tween(240)) + slideInVertically { it / 2 }) {
            Box(Modifier.padding(vertical = 12.dp)) {
                NextDock(ok, title = if (ok) "Верно" else "Прозвучало «${task.target.term}»") { c.audio.stop(); vm.next() }
            }
        }
    }
}

/** Второй этап: произнести звучавшее слово. Whisper выбирает между словами пары — показываем, что он услышал. */
@Composable
private fun SpeakStep(task: PairTask, id: String, onPlayTarget: () -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val recording by recorder.recording.collectAsStateWithLifecycle()
    val level by recorder.level.collectAsStateWithLifecycle()
    val speechModel by c.speech.models.state.collectAsStateWithLifecycle()
    val file = remember(id) { recorder.fileFor("pair-$id") }
    val scope = rememberCoroutineScope()
    var voice by remember(id) { mutableStateOf<PairVoice>(PairVoice.Idle) }
    var job by remember(id) { mutableStateOf<Job?>(null) }
    var hasTake by remember(id) { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    var install by remember { mutableStateOf(false) }
    val micAccess = rememberMicAccess(onDenied = { micDenied = true })

    DisposableEffect(Unit) { onDispose { recorder.clear() } }

    fun onTake() {
        val samples = recorder.samples ?: return
        hasTake = samples.isNotEmpty() && file.exists()
        if (samples.isEmpty()) return
        job?.cancel()
        voice = PairVoice.Checking
        job = scope.launch {
            voice = try {
                // Оба слова в подсказке: Whisper выбирает между ними, не склоняясь к правильному.
                val heard = c.speech.words(samples, hint = "${task.pair.first.term}, ${task.pair.second.term}.")
                PairVoice.Heard(MinimalPairs.judge(heard, task), heard.joinToString(" ") { it.text })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                PairVoice.Failed(e.message?.takeIf { it.any { ch -> ch in 'А'..'я' } } ?: "Не удалось распознать запись. Попробуйте ещё раз.")
            }
        }
    }

    fun toggle() {
        if (recording) { recorder.stop(); return }
        micAccess {
            c.audio.stop()
            job?.cancel()
            voice = PairVoice.Idle
            if (!recorder.start(file, maxMs = 4_000, silenceMs = 700, onStopped = ::onTake)) voice = PairVoice.Failed("Микрофон недоступен.")
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SignalLabel("Теперь скажите «${task.target.term}»")
        Spacer(Modifier.height(12.dp))
        if (!speechModel.ready) {
            Text("Установите Whisper — он покажет, какое слово пары слышно в вашей речи. Работает без интернета.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton({ install = true }) { Text("Установить распознавание речи") }
        } else {
            MicButton(active = recording, level = level, onClick = ::toggle, label = if (recording) "Стоп" else "Сказать", size = 84.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                val v = voice
                when {
                    micDenied -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Message("Для проверки нужен доступ к микрофону.")
                        com.marat.albion.ui.components.AppSettingsButton()
                    }
                    v is PairVoice.Checking -> Eyebrow("Распознаю…")
                    v is PairVoice.Failed -> Message(v.message)
                    v is PairVoice.Heard -> NoteCard(tone = if (v.result == PairSpeech.TARGET) NoteTone.Good else NoteTone.Neutral) {
                        Text(when (v.result) {
                            PairSpeech.TARGET -> "Whisper услышал «${task.target.term}» — то, что нужно."
                            PairSpeech.PARTNER -> "Whisper услышал «${task.partner.term}» — звучит как другое слово пары. Сравните с диктором и попробуйте ещё раз."
                            PairSpeech.OTHER -> "Whisper услышал «${v.text}» — не уверен, какое это слово пары. Попробуйте чётче."
                            PairSpeech.NOTHING -> "Слов не слышно. Говорите громче, ближе к телефону."
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text("Распознавание прощает лёгкий акцент — это подсказка, а не оценка.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Message("Нажмите на микрофон и произнесите слово")
                }
            }
            if (hasTake && !recording) {
                GhostButton("Диктор → я", onClick = {
                    onPlayTarget()
                    scope.launch { c.audio.playFileAfterCurrent(file, "mine:pair-$id") }
                }, icon = AlbionIcons.PlayArrow, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (install) SpeechInstallSheet { install = false }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
