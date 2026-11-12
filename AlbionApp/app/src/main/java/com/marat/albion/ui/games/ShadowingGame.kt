package com.marat.albion.ui.games

import androidx.compose.foundation.shape.CircleShape

import com.marat.albion.ui.components.AlbionIcons
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.marat.albion.ui.components.LocalNarrow
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import com.marat.albion.ui.theme.quietFloatAsState as animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.theme.AlbionFonts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.audio.AudioDecoder
import com.marat.albion.audio.VoiceRecorder
import com.marat.albion.data.audio.Prosody
import com.marat.albion.data.audio.ProsodyAnalyzer
import com.marat.albion.data.audio.ShadowReport
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.theme.AnimatedNumber
import com.marat.albion.data.speech.HeardWord
import com.marat.albion.data.speech.SpokenWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Порог «похоже» для итога игры. */
private const val PASS = 60

/**
 * Шэдоуинг: послушать диктора (можно медленно), повторить — запись остановится сама,
 * когда вы замолчите, — и получить разбор: интонация, ритм, темп, график мелодики и советы.
 * Всё считается на телефоне; записи удаляются после занятия.
 */
@Composable
fun ShadowingRound(vm: GameViewModel, card: CardEntity) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val recording by recorder.recording.collectAsStateWithLifecycle()
    val level by recorder.level.collectAsStateWithLifecycle()
    val playing by c.audio.playing.collectAsStateWithLifecycle()
    val deck = Deck.byId(card.deck) ?: Deck.WORDS
    val file = remember(card.id) { recorder.fileFor(card.id) }
    val scope = rememberCoroutineScope()
    var micDenied by remember { mutableStateOf(false) }
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    val micAccess = rememberMicAccess(onDenied = { micDenied = true })

    val speechModel by c.speech.models.state.collectAsStateWithLifecycle()
    var installSpeech by remember { mutableStateOf(false) }
    // Что звучит в записи диктора: подписи слов на графике и допустимые омофоны (read/red) для проверки.
    val originalWords = remember(card.id) { CompletableDeferred<List<HeardWord>?>() }
    var diktorWords by remember(card.id) { mutableStateOf<List<HeardWord>?>(null) }
    var clarity by remember(card.id) { mutableStateOf<ClarityState>(ClarityState.Idle) }
    var clarityJob by remember(card.id) { mutableStateOf<Job?>(null) }
    // Имя файла озвучки — ровно то, что произносит диктор («read (past).mp3» → «read»).
    val expected = remember(card.id) {
        SpokenWords.split(card.audio?.substringBeforeLast('.')?.substringBefore(" (") ?: card.term).ifEmpty { SpokenWords.split(card.term) }
    }
    var original by remember(card.id) { mutableStateOf<Prosody?>(null) }
    var originalReady by remember(card.id) { mutableStateOf(false) }
    var hasTake by rememberSaveable(card.id) { mutableStateOf(false) }
    var analyzing by remember(card.id) { mutableStateOf(false) }
    var report by remember(card.id) { mutableStateOf<ShadowReport?>(null) }
    var unclear by remember(card.id) { mutableStateOf(false) }
    var best by rememberSaveable(card.id) { mutableStateOf<Int?>(null) }
    var takes by rememberSaveable(card.id) { mutableStateOf(0) }
    // Скользящее окно уровней для «волны» во время записи.
    val wave = remember(card.id) { mutableStateListOf<Float>().apply { repeat(32) { add(0f) } } }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) micDenied = false
    }
    DisposableEffect(Unit) { onDispose { recorder.clear() } }
    LaunchedEffect(card.id) {
        c.audio.playDelayed(deck, card.audio, card.term, "orig:${card.id}", delayMs = 350)
    }
    // Мелодика диктора — один раз на карточку, в фоне.
    LaunchedEffect(card.id) {
        val pcm = card.audio?.let { name -> withContext(Dispatchers.Default) { AudioDecoder.decodeCardAudio(context, deck, name) } }
        original = pcm?.let { withContext(Dispatchers.Default) { ProsodyAnalyzer.analyze(it) } }
        originalReady = true
        val words = if (pcm != null && speechModel.ready) {
            try { c.speech.words(pcm) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } else null
        diktorWords = words
        originalWords.complete(words)
    }
    LaunchedEffect(level, recording) {
        if (recording) { wave.removeAt(0); wave.add(level) }
    }

    fun playOriginal(rate: Float = 1f, key: String = "orig:${card.id}") = c.audio.play(deck, card.audio, card.term, key, rate)
    fun playMine() = c.audio.playFile(file, "mine:${card.id}")

    fun checkClarity(samples: FloatArray) {
        clarityJob?.cancel()
        if (!speechModel.ready) { clarity = ClarityState.Idle; return }
        clarity = ClarityState.Checking
        clarityJob = scope.launch {
            clarity = try {
                val mine = c.speech.words(samples)
                // Если и диктора Whisper записал другим словом (омофон), такое же написание у вас — не ошибка.
                val reference = originalWords.await()?.let { SpokenWords.check(expected, it) }
                val alternatives = reference?.words.orEmpty().map { w -> setOfNotNull(w.heard?.let { SpokenWords.key(it.text) }) }
                ClarityState.Done(SpokenWords.check(expected, mine, alternatives))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ClarityState.Failed(e.message?.takeIf { it.any { ch -> ch in 'А'..'я' } } ?: "Не удалось распознать запись. Попробуйте ещё раз.")
            }
        }
    }

    fun onTake() {
        val samples = recorder.samples ?: return
        hasTake = samples.isNotEmpty() && file.exists()
        if (!hasTake) return
        takes++
        checkClarity(samples)
        val orig = original
        if (orig == null) { playMine(); return }
        analyzing = true
        scope.launch {
            val mine = withContext(Dispatchers.Default) { ProsodyAnalyzer.analyze(samples) }
            val r = mine?.let { withContext(Dispatchers.Default) { ProsodyAnalyzer.compare(orig, it) } }
            unclear = r == null
            report = r
            r?.let { best = maxOf(best ?: 0, it.overall) }
            analyzing = false
        }
    }

    fun toggleRecord() {
        if (analyzing || !originalReady) return
        if (recording) { recorder.stop(); return }
        micAccess {
            c.audio.stop()
            wave.indices.forEach { wave[it] = 0f }
            report = null
            unclear = false
            clarityJob?.cancel()
            clarity = ClarityState.Idle
            if (!recorder.start(file, onStopped = ::onTake)) unclear = true
        }
    }

    fun compare() {
        scope.launch {
            playOriginal()
            c.audio.playFileAfterCurrent(file, "mine:${card.id}")
        }
    }

    fun next() {
        recorder.stop(); c.audio.stop()
        val score = best
        val ok = when {
            score != null -> score >= PASS
            else -> hasTake && original == null // сравнивать не с чем — засчитываем саму запись
        }
        vm.record(ok, if (ok) null else Mistake(card.meaningRu, score?.let { "$it%" } ?: "нет записи", card.term))
        vm.next()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TermBlock(card)
            Spacer(Modifier.height(16.dp))
            ShadowSteps(active = when { recording -> 1; hasTake -> 2; else -> 0 })

            val micSize = if (LocalNarrow.current) 76.dp else 84.dp
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaybackAction(AlbionIcons.PlayArrow, "Диктор", { playOriginal() }, enabled = !recording && !analyzing, active = playing == "orig:${card.id}", slot = micSize + MicLip)
                RecordButton(recording = recording, level = level, onClick = ::toggleRecord, size = micSize, enabled = !analyzing && originalReady)
                PlaybackAction(
                    AlbionIcons.SlowMotionVideo, "Медленно",
                    { playOriginal(0.7f, "slow:${card.id}") },
                    enabled = card.audio != null && !recording && !analyzing, active = playing == "slow:${card.id}", slot = micSize + MicLip,
                )
            }

            // The level strip follows the microphone signal; idle is a quiet baseline.
            AnimatedVisibility(recording || (!hasTake && report == null)) {
                Column(Modifier.fillMaxWidth().border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SignalLabel(if (recording) "Уровень записи" else "Готово к записи", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        wave.forEach { v ->
                            val h by animateFloatAsState(if (recording && !reduced) v.coerceAtLeast(0.1f) else 0.1f, tween(160), label = "bar")
                            Box(Modifier.weight(1f).fillMaxHeight(h).background(if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            when {
                micDenied -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ShadowStatus("Для записи нужен доступ к микрофону.", warm = true)
                    com.marat.albion.ui.components.AppSettingsButton()
                }
                recording -> ShadowStatus("Говорите — запись остановится, когда вы замолчите", warm = true)
                analyzing -> ShadowStatus("Сравниваю с диктором…")
                unclear -> ShadowStatus("Не удалось разобрать запись — говорите громче и ближе к телефону", warm = true)
                report != null -> ShadowStatus("Попытка $takes" + (best?.takeIf { takes > 1 }?.let { " · лучшая $it%" } ?: ""))
                hasTake && original == null -> ShadowStatus("Для этой карточки нет записи диктора — сравните на слух")
                else -> ShadowStatus("Послушайте диктора, затем запишите свой голос")
            }

            AnimatedVisibility(report != null && !recording, enter = fadeIn(tween(320)) + expandVertically()) {
                report?.let { r ->
                    val words = remember(r, diktorWords) { chartWords(diktorWords, original) }
                    ReportCard(r, words, stressTip(r, words))
                }
            }

            AnimatedVisibility(hasTake && !recording && !analyzing, enter = fadeIn(tween(320)) + expandVertically()) {
                SpeechCheckCard(clarity, speechModel.ready) { installSpeech = true }
            }

            AnimatedVisibility(hasTake && !recording && !analyzing, enter = fadeIn()) {
                PlaybackActions(onCompare = ::compare, onPlayMine = ::playMine)
            }
        }
        // До первой записи главное действие — микрофон, поэтому «Пропустить» второстепенная, как в «Произношении».
        if (!recording && !analyzing && originalReady && !hasTake) GhostButton("Пропустить", onClick = ::next, modifier = Modifier.padding(vertical = 12.dp))
        else PrimaryButton(
            when { recording -> "Остановить запись"; analyzing -> "Сравниваем…"; !originalReady -> "Готовим образец…"; else -> "Следующее" },
            onClick = { if (recording) recorder.stop() else next() },
            enabled = !analyzing && originalReady,
            trailingIcon = AlbionIcons.ArrowForward,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    if (installSpeech) SpeechInstallSheet { installSpeech = false }
}

/** Слова диктора на шкале графика; для одного слова подписи не нужны. */
private fun chartWords(words: List<HeardWord>?, original: Prosody?): List<ChartWord> {
    if (words == null || original == null || words.size < 2) return emptyList()
    return words.map { ChartWord(it.text, it.start - original.start, it.end - original.start) }
        .filter { it.to >= 0 && it.from < original.frames }
}

/** Совет об ударении с конкретными словами: какое выделяет диктор, а какое — вы. */
private fun stressTip(r: ShadowReport, words: List<ChartWord>): String? {
    if (words.size < 2 || ProsodyAnalyzer.STRESS_TIP !in r.tips) return null
    val spans = words.map { HeardWord(it.text, it.from, it.to, 1f) }
    val theirs = SpokenWords.wordAt(spans, r.originalEnergy.indices.maxBy { r.originalEnergy[it] }) ?: return null
    val yours = SpokenWords.wordAt(spans, r.mineEnergy.indices.maxBy { r.mineEnergy[it] }) ?: return null
    if (SpokenWords.key(theirs.text) == SpokenWords.key(yours.text)) return null
    return "Диктор сильнее всего выделяет «${theirs.text}», а вы — «${yours.text}». Сделайте ударным «${theirs.text}», остальные слова — легче и короче."
}

@Composable
internal fun SpeechInstallSheet(onDismiss: () -> Unit) {
    com.marat.albion.ui.components.ScrollableSheet("Распознавание речи", onDismiss) {
        com.marat.albion.ui.conversation.SpeechModelControls()
    }
}

@Composable
private fun PlaybackActions(onCompare: () -> Unit, onPlayMine: () -> Unit) {
    val compareLabel = "Диктор → я"
    val mineLabel = "Моя запись"
    val padding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
    Box(Modifier.padding(top = 14.dp)) {
        ButtonPair(
            listOf(compareLabel, mineLabel),
            first = { m -> GhostButton(compareLabel, onCompare, m, contentPadding = padding) },
            second = { m -> GhostButton(mineLabel, onPlayMine, m, contentPadding = padding) },
        )
    }
}

/** Текстовый статус записи, анализа или следующего действия. */
@Composable
private fun ShadowStatus(text: String, warm: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Три шага занятия — пронумерованные «фишки», переносятся на узком экране. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShadowSteps(active: Int = 0) {
    val minStepWidth = 88.dp * LocalDensity.current.fontScale
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Послушайте", "Повторите", "Сравните").forEachIndexed { i, step ->
            Column(
                Modifier.widthIn(min = minStepWidth).weight(1f).background(if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                    .border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                SignalLabel("0${i + 1}", color = if (i == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(step, style = MaterialTheme.typography.labelSmall, color = if (i == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Разбор попытки: общий процент, три шкалы, график мелодики и советы — на мягкой карточке. */
@Composable
internal fun ReportCard(r: ShadowReport, words: List<ChartWord> = emptyList(), stressTip: String? = null) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape).border(.75.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(if (LocalNarrow.current) 16.dp else 18.dp),
    ) {
        SignalLabel("Разбор голоса")
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Result label uses the same square corners as the exercise sheet.
            AnimatedNumber(r.overall, MaterialTheme.typography.displayMedium, suffix = "%")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        r.overall >= 85 -> "Очень близко"
                        r.overall >= 70 -> "Хорошо"
                        r.overall >= PASS -> "Неплохо"
                        else -> "Ещё попытка"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("сходство с диктором", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Это ориентир по сходству ритма, темпа и интонации с этой записью, а не оценка отдельных звуков речи.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.intonation?.let { Scale("Интонация", it, null) }
        Scale("Ритм", r.rhythm, null)
        Scale(
            "Темп", r.tempo,
            when {
                r.tempoRatio > 1.12f -> "медленнее ×${"%.1f".format(r.tempoRatio)}"
                r.tempoRatio < 0.88f -> "быстрее ×${"%.1f".format(1 / r.tempoRatio)}"
                else -> "как у диктора"
            },
        )

        Spacer(Modifier.height(14.dp))
        Eyebrow("Мелодика", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        MelodyChart(r, words)

        if (r.tips.isNotEmpty() || r.overall >= 85) {
            Spacer(Modifier.height(14.dp))
            val tips = r.tips.map { if (it == ProsodyAnalyzer.STRESS_TIP && stressTip != null) stressTip else it }.ifEmpty { listOf("Мелодика, ритм и темп совпадают с диктором. Попробуйте следующее слово.") }
            NoteCard(tone = NoteTone.Neutral) {
                Eyebrow("Советы", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                tips.forEach { tip ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Box(Modifier.padding(top = 9.dp, end = 10.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Monochrome signal scales keep exact values visible next to each measure. */
@Composable
private fun Scale(label: String, value: Int, note: String?) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Пояснение идёт сразу за подписью и переносится целыми словами при крупном шрифте.
            Text(
                buildAnnotatedString {
                    append(label)
                    if (note != null) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = MaterialTheme.typography.bodySmall.fontSize)) { append(" · $note") }
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text("$value%", style = MaterialTheme.typography.titleSmall.copy(fontFamily = AlbionFonts.Tech), color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        ThinProgress(value / 100f, color = if (value >= PASS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, height = 3.dp)
    }
}
