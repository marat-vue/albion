package com.marat.albion.ui.games

import com.marat.albion.ui.components.drawSquareProgress
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.isImeVisible
import com.marat.albion.ui.theme.appear
import com.marat.albion.ui.components.SegmentedPicker
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.Icon
import androidx.compose.animation.core.snap
import com.marat.albion.ui.components.AlbionIcons
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.marat.albion.ui.components.LocalNarrow
import com.marat.albion.ui.components.RoundIconButton
import com.marat.albion.ui.components.gutter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marat.albion.data.content.Deck
import com.marat.albion.ui.components.FeatureHelp
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.theme.AlbionFonts
import com.marat.albion.ui.theme.AnimatedNumber
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.LocalFeedback
import kotlinx.serialization.Serializable

/** Мини-игры раздела «Игры». */
@Serializable
enum class Game(
    val title: String,
    val tagline: String,
    val description: String,
    val decks: List<Deck>,
) {
    TYPE_ANSWER(
        "Ввод ответа",
        "Напишите слово по значению",
        "Видите русское значение и часть речи — напечатайте английское слово. Мелкая опечатка засчитывается как «почти», " +
            "ошибки подсвечиваются по буквам. Подсказка открывает буквы по одной.",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS),
    ),
    IRREGULAR(
        "Неправильные глаголы",
        "Впишите V2 и V3",
        "Дан инфинитив и перевод — впишите Past Simple и Past Participle. Если форм две (abided / abode), " +
            "достаточно любой. Ошибки можно сразу прогнать ещё раз.",
        listOf(Deck.IRREGULAR),
    ),
    SHADOWING(
        "Шэдоуинг",
        "Повторите за диктором — получите разбор",
        "Послушайте диктора (можно замедленно) и повторите — запись остановится сама. Телефон сравнит вашу мелодику, ритм " +
            "и темп с оригиналом, покажет график интонации и подскажет, что поправить. Всё считается на устройстве, записи удаляются после занятия.",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS, Deck.IRREGULAR),
    ),
    PRONUNCIATION(
        "Произношение",
        "Проверьте, узнаётся ли слово",
        "Произнесите слово вслух: встроенное распознавание речи Android проверит, узнаётся ли оно. " +
            "Без интернета работает, если в настройках Google скачан офлайн-пакет английского языка.",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS),
    ),
    GRAMMAR_CHOICE(
        "Грамматический тест",
        "Выберите верный вариант",
        "Задания по всем темам учебника — от A1 до C1. Выберите правильный вариант, прочитайте пояснение " +
            "и, если нужно, откройте правило. Задания, в которых вы ошибались, попадаются чаще.",
        emptyList(),
    ),
    GRAMMAR_GAP(
        "Впишите пропуск",
        "Впишите пропущенное в предложение",
        "В предложении пропуск — впишите нужную форму сами, без подсказок-вариантов. В скобках дана " +
            "начальная форма или перевод. Регистр и лишние пробелы не важны.",
        emptyList(),
    ),
    SENTENCE(
        "Соберите предложение",
        "Расставьте слова по порядку",
        "Слова примера из карточки перемешаны — касайтесь их по порядку, чтобы собрать предложение. Тренирует порядок " +
            "слов, вопросы и место наречий. Нажмите на слово в ответе, чтобы вернуть его обратно.",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS),
    ),
    TENSES(
        "Тренажёр времён",
        "Какая форма глагола нужна?",
        "Предложения из таблицы неправильных глаголов с пропуском: выберите или впишите V1, V2 или V3. " +
            "После ответа — правило: почему после have нужна третья форма, а после did — первая.",
        listOf(Deck.IRREGULAR),
    ),
    DICTATION(
        "Диктант",
        "Запишите слово на слух",
        "Диктор произносит слово или выражение — напечатайте, что услышали. Можно переслушать и замедлить. " +
            "Ошибки подсвечиваются по буквам; значение слова — после ответа. Тренирует слух и написание.",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS, Deck.IRREGULAR),
    ),
    CLOZE(
        "Слово в контексте",
        "Вставьте слово в пример",
        "Пример из карточки с пропуском и русское значение — впишите слово в нужной форме (looked, studies). " +
            "Верное слово в другой форме засчитывается как «почти». Подошедшие повторения засчитываются в «Сегодня».",
        listOf(Deck.WORDS, Deck.PHRASAL, Deck.IDIOMS, Deck.COLLOCATIONS),
    ),
    FIX_ERROR(
        "Исправьте ошибку",
        "Найдите ошибку и исправьте её",
        "В предложении одна ошибка — типичная для русскоязычных или из заданий учебника. Коснитесь неверного слова, " +
            "затем впишите правильный вариант. После ответа — короткое объяснение.",
        emptyList(),
    );

    val isGrammar get() = this == GRAMMAR_CHOICE || this == GRAMMAR_GAP

    /**
     * Ответ проверяет вспоминание слова по значению — подошедшее повторение засчитывается в FSRS.
     * Формы глаголов, диктант, выбор, плашки и речь память слова не проверяют.
     */
    val gradesMemory get() = this == TYPE_ANSWER || this == CLOZE

    val icon: ImageVector
        get() = when (this) {
            TYPE_ANSWER -> AlbionIcons.Keyboard
            IRREGULAR -> AlbionIcons.List
            SHADOWING -> AlbionIcons.RecordVoiceOver
            PRONUNCIATION -> AlbionIcons.Mic
            GRAMMAR_CHOICE -> AlbionIcons.Rule
            GRAMMAR_GAP -> AlbionIcons.EditNote
            SENTENCE -> AlbionIcons.ViewWeek
            TENSES -> AlbionIcons.History
            DICTATION -> AlbionIcons.VolumeUp
            CLOZE -> AlbionIcons.TextFields
            FIX_ERROR -> AlbionIcons.BorderColor
        }

    /** Как называть единицу занятия: «10 слов», «10 фраз». */
    val unit: String get() = when (this) {
        SENTENCE, FIX_ERROR -> "предложений"
        IRREGULAR -> "глаголов"
        TENSES, GRAMMAR_CHOICE, GRAMMAR_GAP -> "заданий"
        else -> "слов"
    }

    /** Режимы на экране настройки (если у игры их несколько). */
    val modes: List<String> get() = if (this == TENSES) listOf("Выбор формы", "Ввод") else emptyList()
}

/** Экран перед началом: крупная плитка упражнения, параметры капсулами, «Начать» у большого пальца. */
@Composable
fun GameSetup(
    game: Game,
    deck: Deck,
    count: Int,
    onDeck: (Deck) -> Unit,
    onCount: (Int) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    counts: List<Int> = listOf(10, 20, 30),
    mode: Int = 0,
    onMode: (Int) -> Unit = {},
    mistakes: Int = 0,
    onlyMistakes: Boolean = false,
    onOnlyMistakes: (Boolean) -> Unit = {},
    available: Int? = null,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack = onBack) {
            FeatureHelp("practice.${game.name}", "Как заниматься", game.description +
                "\n\nОшибки сохраняются. Сложные слова встретятся первыми, а после двух верных ответов подряд уйдут из списка ошибок.")
        }
        SetupBody(
            start = {
                val length = if (count == Int.MAX_VALUE) "${available ?: ""} ${game.unit}".trim() else "$count ${game.unit}"
                PrimaryButton("Начать", onClick = onStart, trailingIcon = AlbionIcons.ArrowForward)
                Text(length, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            },
        ) {
            GameSetupHeader(game) {
                if (game.decks.size > 1) {
                    SetupLabel("Словарь")
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        game.decks.forEach { d -> com.marat.albion.ui.components.ChoiceRow(d.title, d == deck, { onDeck(d) }) }
                    }
                }
                if (game.modes.isNotEmpty()) {
                    SetupLabel("Режим", if (game.decks.size > 1) 2 else 1)
                    SegmentedPicker(game.modes, mode, onMode)
                }
                SetupLabel("Длина", 1 + (if (game.decks.size > 1) 1 else 0) + (if (game.modes.isNotEmpty()) 1 else 0))
                val labels = counts.map { n -> if (n == Int.MAX_VALUE) "Все" else "$n" }
                SegmentedPicker(labels, counts.indexOf(count).coerceAtLeast(0), { onCount(counts[it]) })
                if (count == Int.MAX_VALUE) Text("${available ?: "Все"} ${game.unit} · около ${((available ?: 0) / 4).coerceAtLeast(1)} мин. Можно закончить раньше.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mistakes > 0) {
                    Spacer(Modifier.height(20.dp))
                    com.marat.albion.ui.components.Panel(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                        com.marat.albion.ui.settings.SwitchSetting("Только ошибки · $mistakes", onlyMistakes, onOnlyMistakes)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupLabel(text: String, number: Int = 1) {
    PracticeSectionLabel(number.toString().padStart(2, '0'), text)
}

/**
 * Тело экрана настройки: параметры прокручиваются, кнопка «Начать» закреплена внизу —
 * на маленьком экране её не нужно искать прокруткой.
 */
@Composable
internal fun ColumnScope.SetupBody(start: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = gutter).padding(bottom = 16.dp),
        content = content,
    )
    if (start != null) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = gutter).padding(top = 8.dp, bottom = 16.dp)) {
            PracticeActionShelf { start() }
        }
    } else {
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** Шапка во время игры: закрыть, сегментированный прогресс и счётчик. */
@Composable
fun GameTopRow(game: Game?, index: Int, total: Int, onClose: () -> Unit, title: String = game?.title.orEmpty()) {
    val typing = imeOpen()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).semantics(mergeDescendants = false) {}) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = gutter, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            com.marat.albion.ui.components.RoundIconButton(AlbionIcons.Close, "Закрыть", onClose, filled = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SignalLabel(game?.let { "${it.editionCode} / ${it.editionTitle}" } ?: "УЧЕБНИК / ПРОВЕРКА")
                if (title.isNotBlank() && !typing) {
                    Spacer(Modifier.height(4.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("${(index + 1).coerceAtMost(total)} / $total", style = MaterialTheme.typography.labelLarge.copy(fontFamily = AlbionFonts.Tech), maxLines = 1)
        }
        SegmentedProgress(index, total, Modifier.padding(horizontal = gutter).padding(bottom = 4.dp))
    }
}

/**
 * Выход из начатого раунда (крестик или системное «назад») спрашивает подтверждение:
 * ответы уже сохранены, но итог сессии пропадёт. До первого ответа выходим сразу.
 */
@Composable
internal fun rememberLeaveGuard(active: Boolean, onLeave: () -> Unit): () -> Unit {
    var asking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val request = { if (active) asking = true else onLeave() }
    androidx.activity.compose.BackHandler(onBack = request)
    if (asking) com.marat.albion.ui.components.PaneAlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Прервать тренировку?") },
        text = { Text("Ответы уже сохранены, но итог этой сессии не будет показан.") },
        confirmButton = {
            com.marat.albion.ui.components.TextButton(onClick = { asking = false; onLeave() }) {
                Text("Прервать", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { com.marat.albion.ui.components.TextButton(onClick = { asking = false }) { Text("Продолжить") } },
    )
    return request
}

/** Прогресс сессии: сегменты по числу вопросов (до 30), иначе — сплошная полоса. */
@Composable
internal fun SegmentedProgress(index: Int, total: Int, modifier: Modifier = Modifier) {
    if (total <= 0 || total > 30) {
        ThinProgress(if (total == 0) 0f else index.toFloat() / total, modifier, height = 3.dp)
        return
    }
    val reduced = LocalReducedMotion.current
    Row(modifier.height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            val fill by androidx.compose.animation.animateColorAsState(
                if (i < index) MaterialTheme.colorScheme.primary
                else if (i == index) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                if (reduced) snap() else com.marat.albion.ui.theme.Springs.smooth(), label = "segment$i")
            Box(Modifier.weight(1f).fillMaxHeight().background(fill, RectangleShape))
        }
    }
}

/** Равные кнопки ответа; при крупном системном шрифте встают друг под другом. */
@Composable
internal fun GameAnswerActions(
    onCheck: () -> Unit,
    onSecondary: () -> Unit,
    secondaryLabel: String = "Не знаю",
) {
    ButtonPair(
        listOf("Проверить", secondaryLabel),
        first = { m -> PrimaryButton("Проверить", onCheck, m) },
        second = { m -> GhostButton(secondaryLabel, onSecondary, m, contentPadding = PairPadding) },
    )
}

private val PairPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)

/**
 * Две кнопки рядом, если подписи помещаются в половину ширины целиком; иначе — друг под другом.
 * Так слово никогда не рвётся по буквам («Проверит-ь») при крупном шрифте и на узком экране.
 */
@Composable
internal fun ButtonPair(
    labels: List<String>,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val style = MaterialTheme.typography.titleMedium
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = labels.maxOf { measurer.measure(it, style, softWrap = false).size.width }
    // Поля основной кнопки — по 20 dp с каждой стороны, плюс запас на округление.
    val need = with(density) { widest.toDp() } + 48.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if ((maxWidth - 10.dp) / 2 >= need) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.weight(1f).fillMaxHeight())
                second(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier)
                second(Modifier)
            }
        }
    }
}

data class Mistake(val prompt: String, val answer: String, val correct: String, val promptIsIpa: Boolean = false)

/** Шапка настройки: крупная плитка-иконка, название акцентным шрифтом, описание. */
@Composable
internal fun GameSetupHeader(game: Game, modifier: Modifier = Modifier, options: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Panel(Modifier.fillMaxWidth().appear(0), contentPadding = PaddingValues(20.dp)) {
            SignalLabel("${game.editionCode} / ${game.editionTitle}")
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(game.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (!LocalNarrow.current && LocalDensity.current.fontScale <= 1.3f) {
                    Spacer(Modifier.width(12.dp))
                    PracticeEmblem(game)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(game.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().appear(3), content = options)
    }
}

/** Итог: кольцо результата прорисовывается на пружине, проценты катятся, ошибки — карточками. */
@Composable
fun GameSummary(
    game: Game?, correct: Int, total: Int, mistakes: List<Mistake>,
    onRestart: () -> Unit, onRetryMistakes: (() -> Unit)?, onDone: () -> Unit,
    scoreLabel: String = "верно", title: String = game?.title.orEmpty(), restartLabel: String = "Ещё раз",
    /** Пояснение под итогом — например, сколько ответов засчитано в повторения. */
    note: String? = null,
) {
    val feedback = LocalFeedback.current
    LaunchedEffect(Unit) { if (total > 0) feedback.complete() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (total == 0) com.marat.albion.ui.components.EmptyState("Нет заданий для этих параметров", "Измените словарь, уровень или режим упражнения.")
            else {
                SignalLabel("СЕССИЯ ЗАВЕРШЕНА", Modifier.appear(0))
                Spacer(Modifier.height(8.dp))
                Text(when { correct == total -> "Безупречно."; correct * 10 >= total * 7 -> "Отличная работа."; else -> "Продолжайте." },
                    Modifier.appear(1), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                PracticeStage(title, centered = true, modifier = Modifier.appear(2)) {
                    ResultRing(correct, total)
                    Spacer(Modifier.height(18.dp))
                    Text("$correct из $total · $scoreLabel", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                if (LocalDensity.current.fontScale > 1.3f) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryMetric("БЕЗ ОШИБОК", correct, Modifier.fillMaxWidth())
                        SummaryMetric("К ПОВТОРЕНИЮ", (total - correct).coerceAtLeast(0), Modifier.fillMaxWidth())
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryMetric("БЕЗ ОШИБОК", correct, Modifier.weight(1f))
                    SummaryMetric("К ПОВТОРЕНИЮ", (total - correct).coerceAtLeast(0), Modifier.weight(1f))
                }
                note?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (mistakes.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Ошибки", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text("сохранены для повтора", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        mistakes.forEachIndexed { i, m -> SummaryMistake(m, Modifier.appear(3 + i)) }
                    }
                }
            }
        }
        if (total == 0) PrimaryButton("Другие упражнения", onDone, Modifier.padding(bottom = 16.dp))
        else {
            if (mistakes.isNotEmpty() && onRetryMistakes != null) {
                PrimaryButton("Повторить ошибки", onRetryMistakes, icon = AlbionIcons.Undo)
                Spacer(Modifier.height(8.dp))
                GhostButton("Готово", onDone)
            } else PrimaryButton("Готово", onDone)
            com.marat.albion.ui.components.TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) { Text(restartLabel) }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Panel(modifier, contentPadding = PaddingValues(16.dp)) {
        SignalLabel(label)
        Spacer(Modifier.height(8.dp))
        AnimatedNumber(value, MaterialTheme.typography.headlineMedium)
    }
}

/** Кольцо результата: дуга дорисовывается от 0 до доли верных ответов. */
@Composable
private fun ResultRing(correct: Int, total: Int, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val target = if (total == 0) 0f else correct.toFloat() / total
    val sweep = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target, reduced) { sweep.animateTo(target, if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 60f)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary
    Box(modifier.size(164.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawSquareProgress(track, arc, if (reduced) target else sweep.value, 2.dp.toPx(), inset = 8.dp.toPx())
        }
        com.marat.albion.ui.theme.AnimatedNumber(if (total == 0) 0 else correct * 100 / total,
            if (LocalDensity.current.fontScale > 1.3f) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium, suffix = "%")
    }
}

@Composable
private fun SummaryMistake(m: Mistake, modifier: Modifier = Modifier) {
    com.marat.albion.ui.components.Panel(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        if (m.prompt.isNotBlank()) Text(m.prompt,
            style = if (m.promptIsIpa) com.marat.albion.ui.theme.IpaStyle else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (m.promptIsIpa) Int.MAX_VALUE else 2)
        Text(m.correct, style = MaterialTheme.typography.titleMedium)
        if (m.answer.isNotBlank()) Text("Ваш ответ: ${m.answer}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** Запрос доступа к микрофону; [run] выполняется сразу, если доступ уже есть. */
@Composable
fun rememberMicAccess(onDenied: () -> Unit = {}): (run: () -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder[0]?.invoke() else onDenied()
        holder[0] = null
    }
    return { run ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) run()
        else {
            holder[0] = run
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** «Встряхнуть» элемент при неверном ответе: значение [trigger] меняется — элемент дрожит. */
fun Modifier.shakeOn(trigger: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val offset = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { 3.dp.toPx() }
    LaunchedEffect(trigger, reduced) {
        if (trigger == 0 || reduced) { offset.snapTo(0f); return@LaunchedEffect }
        for (k in listOf(1f, -0.5f, 0f)) offset.animateTo(k * px, tween(55))
    }
    graphicsLayer { translationX = if (reduced) 0f else offset.value }
}

// ------------------------------------------------------------------ общие элементы раунда

/** Контурная рабочая поверхность; при открытой клавиатуре убирает второстепенные отступы. */
@Composable
internal fun TaskCard(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    label: String = "ЗАДАНИЕ",
    content: @Composable ColumnScope.() -> Unit,
) {
    PracticeStage(label, modifier.appear(), centered = centered, compact = imeOpen(), content = content)
}

/** Тон плашки разбора. */
internal enum class NoteTone { Neutral, Good, Bad }

/** Разбор ответа: отдельная контурная панель с правилом или правильной формой. */
@Composable
internal fun NoteCard(
    modifier: Modifier = Modifier,
    tone: NoteTone = NoteTone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = when (tone) {
        NoteTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerLow
        NoteTone.Good -> MaterialTheme.colorScheme.tertiaryContainer
        NoteTone.Bad -> MaterialTheme.colorScheme.errorContainer
    }
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg)
            .border(.75.dp, when (tone) {
                NoteTone.Neutral -> MaterialTheme.colorScheme.outlineVariant
                NoteTone.Good -> MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)
                NoteTone.Bad -> MaterialTheme.colorScheme.error.copy(alpha = .5f)
            }, MaterialTheme.shapes.large)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

/**
 * Нижняя панель после ответа: тонированная полоса выезжает снизу — значок, вердикт и «Дальше».
 * [ok] = null — нейтральный итог (например, после разбора шэдоуинга).
 */
@Composable
internal fun NextDock(ok: Boolean?, title: String? = null, label: String = "Дальше", onNext: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    val offset = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(reduced) { offset.animateTo(0f, if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 320f)) }
    val (bg, fg) = when (ok) {
        true -> scheme.surfaceContainerLow to scheme.tertiary
        false -> scheme.surfaceContainerLow to scheme.error
        null -> scheme.surfaceContainerLow to scheme.onSurface
    }
    val heading = title ?: when (ok) { true -> "Верно"; false -> "Неверно"; null -> "Готово" }
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationY = if (reduced) 0f else offset.value * size.height; alpha = if (reduced) 1f else 1f - offset.value * 0.6f }
            .clip(MaterialTheme.shapes.large).background(bg)
            .border(.75.dp, scheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(16.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(fg, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                Icon(if (ok == false) AlbionIcons.Close else AlbionIcons.Check, null, Modifier.size(16.dp), tint = bg)
            }
            Spacer(Modifier.width(10.dp))
            Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = fg)
            if (ok == false) SignalLabel("РАЗБОР")
        }
        PrimaryButton(label, onClick = onNext, trailingIcon = AlbionIcons.ArrowForward)
    }
}

/** Крупный текст условия: на узком экране чуть мельче, чтобы предложение не превращалось в столбик. */
internal val promptStyle: TextStyle
    @Composable get() = if (LocalNarrow.current) MaterialTheme.typography.headlineSmall
    else MaterialTheme.typography.headlineMedium

/** Крупный термин (слово, ответ): размер зависит от длины и ширины экрана. */
@Composable
internal fun termStyle(text: String, max: Int = 48): TextStyle {
    val size = when {
        text.length <= 10 -> max
        text.length <= 20 -> max * 4 / 5
        else -> max * 5 / 8
    }.let { if (LocalNarrow.current) it * 5 / 6 else it }
    return TextStyle(fontFamily = AlbionFonts.Display, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = size.sp, lineHeight = (size * 1.18f).sp, textAlign = TextAlign.Center)
}

/** Открыта ли клавиатура: пока пользователь печатает, раунд прячет второстепенное, чтобы задание оставалось видно. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun imeOpen(): Boolean = WindowInsets.isImeVisible
