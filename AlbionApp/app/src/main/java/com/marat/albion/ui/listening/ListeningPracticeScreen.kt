package com.marat.albion.ui.listening

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.audio.AudioDecoder
import com.marat.albion.audio.AudioPlayer
import com.marat.albion.data.games.Verdict
import com.marat.albion.data.listening.ClozeItem
import com.marat.albion.data.listening.Dictation
import com.marat.albion.data.listening.DictationResult
import com.marat.albion.data.listening.ListeningCloze
import com.marat.albion.data.listening.ListeningPracticeMode
import com.marat.albion.data.listening.ListeningTrack
import com.marat.albion.data.listening.Segment
import com.marat.albion.data.speech.SpokenWords
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.EmptyState
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.LoadingState
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.games.ShadowReference
import com.marat.albion.ui.games.ShadowingPanel
import com.marat.albion.ui.theme.AlbionFonts
import kotlinx.coroutines.CancellationException

/** Сколько фраз в одном занятии. */
private const val ROUND = 10

/** Запас по краям фразы: граница выравнивания может срезать первый или последний звук. */
private const val LEAD_MS = 80L
private const val TAIL_MS = 160L

/** Задание практики — фраза трека (и пропуск для cloze). */
private data class Task(val index: Int, val segment: Segment, val cloze: ClozeItem? = null)

/**
 * Практика по треку: диктант по фразе, пропуски на словах из ваших коллекций и шэдоуинг.
 * Фразы звучат отрезками той же записи; результат — итог занятия, в FSRS он не записывается.
 */
@Composable
fun ListeningPracticeScreen(trackId: String, mode: ListeningPracticeMode, fromSegment: Int, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    var track by remember { mutableStateOf<ListeningTrack?>(null) }
    var tasks by remember { mutableStateOf<List<Task>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var correct by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(trackId, mode, fromSegment) {
        try {
            val t = c.listeningLibrary.track(trackId) ?: run { error = "Запись не найдена"; return@LaunchedEffect }
            val transcript = c.listeningLibrary.transcript(t)
            val segments = transcript.segments
            fun around(words: IntRange) = (segments.indices.drop(fromSegment) + segments.indices.take(fromSegment))
                .filter { SpokenWords.split(segments[it].text).size in words }.take(ROUND)
            tasks = when (mode) {
                ListeningPracticeMode.DICTATION -> around(3..18).map { Task(it, segments[it]) }
                ListeningPracticeMode.SHADOWING -> around(2..14).map { Task(it, segments[it]) }
                // Постоянное зерно: после поворота экрана набор тот же, и сохранённый номер шага остаётся верным.
                ListeningPracticeMode.CLOZE -> ListeningCloze.build(transcript, c.listeningLibrary.collectionMarker(), ROUND,
                    kotlin.random.Random(trackId.hashCode() * 31 + fromSegment))
                    .map { Task(it.segment, segments[it.segment], it) }
            }
            track = t
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Не удалось открыть запись" }
    }
    // Звук фразы не должен продолжаться после выхода из практики.
    DisposableEffect(Unit) { onDispose { c.audio.stop() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack, mode.title)
        val t = track
        val list = tasks
        when {
            error != null -> EmptyState("Практика недоступна", error.orEmpty(), Modifier.padding(gutter))
            t == null || list == null -> LoadingState("Готовлю фразы")
            list.isEmpty() -> EmptyState(
                if (mode == ListeningPracticeMode.CLOZE) "Нет слов из коллекций" else "Нет подходящих фраз",
                if (mode == ListeningPracticeMode.CLOZE) "В этом тексте не встретились слова из ваших коллекций. Добавьте слова в коллекцию или выберите другой рассказ."
                else "В тексте нет фраз подходящей длины.",
                Modifier.padding(gutter),
            )
            step >= list.size -> Summary(correct, list.size, mode, onBack, onAgain = { step = 0; correct = 0 })
            else -> {
                val task = list[step]
                Column(Modifier.padding(horizontal = gutter)) {
                    Eyebrow("${step + 1} / ${list.size}")
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(step.toFloat() / list.size)
                }
                val next: (Boolean) -> Unit = { ok -> if (ok) correct++; step++ }
                when (mode) {
                    ListeningPracticeMode.DICTATION -> DictationRound(t, task, next)
                    ListeningPracticeMode.CLOZE -> ClozeRound(t, task, requireNotNull(task.cloze) { "Пропуск не построен" }, next)
                    ListeningPracticeMode.SHADOWING -> {
                        val reference = remember(t.id, task.index) { SegmentShadowReference(t, task.segment, task.index) }
                        ShadowingPanel(reference, header = { PhraseHeader(task.segment.text) }) { ok, _ -> next(ok) }
                    }
                }
            }
        }
    }
}

/** Фраза трека как эталон шэдоуинга: отрезок записи вместо отдельного файла. */
private class SegmentShadowReference(private val track: ListeningTrack, private val segment: Segment, index: Int) : ShadowReference {
    override val id: String = "listen_${track.id}_$index"
    override val hasAudio: Boolean = true
    private val start = (segment.s - LEAD_MS).coerceAtLeast(0)
    private val end = segment.e + TAIL_MS

    override fun expectedWords(): List<String> = SpokenWords.split(Dictation.forDictation(segment.text))
    override fun play(audio: AudioPlayer, key: String, rate: Float) = audio.playClip(track.audioUri, start, end, key, rate)
    override suspend fun playDelayed(audio: AudioPlayer, key: String, delayMs: Long) = audio.playClipDelayed(track.audioUri, start, end, key, delayMs)
    override fun decode(context: Context): FloatArray? =
        AudioDecoder.decodeClip(context, track.assetAudioPath.takeIf { track.bundled }, track.audioFile.takeIf { !track.bundled }, start, end)
}

@Composable
private fun PhraseHeader(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow("Слушайте и повторяйте")
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleLarge.copy(fontFamily = AlbionFonts.Reading))
    }
}

/** Кнопки «Слушать» и «Медленно» для фразы. */
@Composable
private fun PhraseAudio(track: ListeningTrack, task: Task, autoplay: Boolean) {
    val c = LocalAppContainer.current
    val playing by c.audio.playing.collectAsStateWithLifecycle()
    val start = (task.segment.s - LEAD_MS).coerceAtLeast(0)
    val end = task.segment.e + TAIL_MS
    val key = "listen:${track.id}:${task.index}"
    LaunchedEffect(key, autoplay) { if (autoplay) c.audio.playClipDelayed(track.audioUri, start, end, key, 350) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(if (playing == key) "Звучит…" else "Слушать", { c.audio.playClip(track.audioUri, start, end, key) },
            Modifier.weight(1f), icon = AlbionIcons.VolumeUp)
        GhostButton("Медленно", { c.audio.playClip(track.audioUri, start, end, "$key:slow", 0.75f) },
            Modifier.weight(1f), icon = AlbionIcons.SlowMotionVideo)
    }
}

@Composable
private fun DictationRound(track: ListeningTrack, task: Task, onNext: (Boolean) -> Unit) {
    var answer by rememberSaveable(task.index) { mutableStateOf("") }
    var result by remember(task.index) { mutableStateOf<DictationResult?>(null) }
    val check = { result = Dictation.check(answer, task.segment.text) }
    PracticeColumn(
        bottom = {
            val r = result
            if (r == null) {
                PrimaryButton("Проверить", { check() }, enabled = answer.isNotBlank())
                GhostButton("Показать текст", { result = Dictation.check("", task.segment.text) }, Modifier.padding(top = 8.dp))
            } else PrimaryButton("Дальше", { onNext(r.passed) }, trailingIcon = AlbionIcons.ArrowForward)
        },
    ) {
        Text("Запишите, что слышите. Регистр и знаки препинания не важны.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PhraseAudio(track, task, autoplay = true)
        OutlinedTextField(
            answer, { answer = it }, Modifier.fillMaxWidth(), enabled = result == null, minLines = 3,
            label = { Text("Ваш вариант") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = AlbionFonts.Reading),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (answer.isNotBlank()) check() }),
        )
        result?.let { r ->
            VerdictLine(r.passed, if (r.passed) "Верно · ${(r.accuracy * 100).toInt()}%" else "Совпало ${(r.accuracy * 100).toInt()}%")
            Text(annotateDictation(r), style = MaterialTheme.typography.titleMedium.copy(fontFamily = AlbionFonts.Reading))
            if (r.missedWords.isNotEmpty() && r.accuracy > 0f) Text(
                "Не записано: " + r.missedWords.distinct().joinToString(", ") { "«$it»" } +
                    ". Короткие слова в речи звучат слабо — послушайте фразу ещё раз медленно.",
                style = MaterialTheme.typography.bodyMedium)
            if (!r.passed) Text("Подчёркнуто то, что пропущено или написано иначе.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClozeRound(track: ListeningTrack, task: Task, item: ClozeItem, onNext: (Boolean) -> Unit) {
    var answer by rememberSaveable(task.index) { mutableStateOf("") }
    var verdict by remember(task.index) { mutableStateOf<Verdict?>(null) }
    val check = { verdict = ListeningCloze.check(answer, item) }
    PracticeColumn(
        bottom = {
            val v = verdict
            if (v == null) {
                PrimaryButton("Проверить", { check() }, enabled = answer.isNotBlank())
                GhostButton("Не знаю", { verdict = Verdict.Wrong }, Modifier.padding(top = 8.dp))
            } else PrimaryButton("Дальше", { onNext(v != Verdict.Wrong) }, trailingIcon = AlbionIcons.ArrowForward)
        },
    ) {
        Text("Послушайте фразу и впишите пропущенное слово — оно из ваших коллекций.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PhraseAudio(track, task, autoplay = true)
        val scheme = MaterialTheme.colorScheme
        Text(
            buildAnnotatedString {
                append(item.before)
                if (verdict == null) withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append("_".repeat(item.answer.length.coerceIn(4, 12))) }
                else withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(item.answer) }
                append(item.after)
            },
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = AlbionFonts.Reading),
        )
        OutlinedTextField(
            answer, { answer = it }, Modifier.fillMaxWidth(), enabled = verdict == null, singleLine = true,
            label = { Text("Пропущенное слово") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (answer.isNotBlank()) check() }),
        )
        verdict?.let { v ->
            VerdictLine(v != Verdict.Wrong, when (v) { Verdict.Correct -> "Верно"; Verdict.Typo -> "Почти: «${item.answer}»"; Verdict.Wrong -> "Правильно: «${item.answer}»" })
        }
    }
}

@Composable
private fun PracticeColumn(bottom: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { bottom() }
    }
}

/** Единственный цвет в интерфейсе — сигнал верно/ошибка. */
@Composable
private fun VerdictLine(ok: Boolean, text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Текст фразы, где пропущенные или искажённые буквы подчёркнуты (как в «Письме»). */
private fun annotateDictation(r: DictationResult): AnnotatedString = buildAnnotatedString {
    r.expected.forEachIndexed { i, ch ->
        if (r.mask[i]) withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)) { append(ch) } else append(ch)
    }
}

@Composable
private fun Summary(correct: Int, total: Int, mode: ListeningPracticeMode, onBack: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(mode.title)
        Text("$correct из $total", style = MaterialTheme.typography.displaySmall)
        Text(
            when {
                total == 0 -> ""
                correct * 10 >= total * 8 -> "Отлично. Попробуйте следующий отрывок или скорость 1.25×."
                correct * 2 >= total -> "Хороший результат. Сложные фразы можно повторить в плеере с повтором фразы."
                else -> "Послушайте эти фразы ещё раз с повтором и на скорости 0.75×, затем вернитесь к практике."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("К записи", onBack)
        GhostButton("Ещё раз", onAgain)
    }
}
