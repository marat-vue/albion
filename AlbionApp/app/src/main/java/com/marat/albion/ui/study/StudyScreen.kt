package com.marat.albion.ui.study

import com.marat.albion.ui.theme.AlbionFonts
import androidx.compose.ui.text.font.FontStyle

import com.marat.albion.ui.theme.controlBorder
import com.marat.albion.ui.library.pluralRu

import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.marat.albion.ui.theme.LocalFeedback
import com.marat.albion.ui.theme.LocalReducedMotion

import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.gutter
import androidx.activity.compose.BackHandler
import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import androidx.compose.animation.core.Animatable
import com.marat.albion.ui.theme.quietFloatAsState as animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.marat.albion.ui.components.PaneAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.LocalAppContainer
import com.marat.albion.audio.AudioPlayer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.repo.StudySource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.settings.Settings
import com.marat.albion.ui.components.StatPill
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.Easing
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun rememberCardAudio(player: AudioPlayer): CardAudio {
    val playing by player.playing.collectAsStateWithLifecycle()
    return remember(player, playing) {
        object : CardAudio {
            override val playingKey: String? = playing
            override fun play(deck: Deck, file: String?, text: String, key: String) = player.play(deck, file, text, key)
        }
    }
}

@Composable
fun StudyScreen(source: StudySource, navigator: Navigator, answerLimit: Int? = null) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "study-$source-${answerLimit ?: "full"}") { StudyViewModel(c.study, source, answerLimit) }
    val collectionName by remember(source) {
        (source as? StudySource.Collection)?.let { s -> c.collections.observe(s.id).map { it?.name } } ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(Settings())
    val audio = rememberCardAudio(c.audio)
    val feedback = LocalFeedback.current
    val reduced = LocalReducedMotion.current

    val current = state.current
    LaunchedEffect(current?.card?.id, state.revealed) {
        val card = current?.card ?: return@LaunchedEffect
        val d = Deck.byId(card.deck) ?: Deck.WORDS
        if ((!state.revealed && settings.autoplayFront) || (state.revealed && settings.autoplayBack)) {
            c.audio.playDelayed(d, card.audio, card.term, "term:${card.id}", delayMs = 180)
        }
    }

    val rate: (Rating) -> Unit = { rating ->
        // «Снова» — отказ, остальные оценки — подтверждение.
        if (rating == Rating.Again) feedback.wrong() else feedback.correct()
        c.audio.stop()
        vm.answer(rating)
    }

    BackHandler { c.audio.stop(); navigator.back() }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("Что-то пошло не так") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("Понятно") } },
        )
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        val compact = maxHeight < 480.dp || LocalDensity.current.fontScale > 1.3f
        Column(Modifier.fillMaxSize()) {
            TopRow(studyTitle(source, collectionName, state), state, compact, onClose = { c.audio.stop(); navigator.back() }, onUndo = vm::undo)
            ThinProgress(state.progress, modifier = Modifier.padding(horizontal = gutter))

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.marat.albion.ui.components.LoadingState("Готовим карточки")
                }
                state.finished -> Finished(state, onDone = { navigator.back() }, onUndo = vm::undo)
                current != null -> {
                    StudyWorkspace(Modifier.weight(1f), card = { modifier ->
                        Box(modifier.padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 8.dp else 18.dp)) {
                            CardStackShadow(remaining = state.remaining)
                            AnimatedContent(
                                targetState = current,
                                contentKey = { it.card.id + it.isNew },
                                transitionSpec = {
                                    val dir = if (state.lastRating == Rating.Again) -1 else 1
                                    (fadeIn(tween(180, delayMillis = 30)) +
                                        scaleIn(tween(240, easing = Easing.Emphasized), initialScale = if (reduced) 1f else 0.995f) +
                                        slideInVertically(tween(240, easing = Easing.Emphasized)) { if (reduced) 0 else it / 40 }) togetherWith
                                        (slideOutHorizontally(tween(220, easing = Easing.EmphasizedAccelerate)) { if (reduced) 0 else dir * it / 7 } +
                                            fadeOut(tween(160)) +
                                            scaleOut(tween(220), targetScale = if (reduced) 1f else 0.995f))
                                },
                                label = "card",
                            ) { sc ->
                                val isCurrent = sc.card.id == current.card.id
                                SwipeToRate(enabled = isCurrent && state.revealed, onRate = rate) {
                                    FlipCard(
                                        revealed = if (isCurrent) state.revealed else true,
                                        onReveal = { feedback.reveal(); vm.reveal() },
                                        showHint = !compact,
                                        front = { CardFront(sc.card, sc.irregular, audio, settings.showIpaOnFront) },
                                        back = { CardBack(sc.card, sc.irregular, audio, showRussian = settings.showRussian) },
                                    )
                                }
                            }
                        }
                    }, actions = { modifier ->
                        Box(modifier.padding(start = if (compact) 12.dp else 20.dp, end = if (compact) 12.dp else 20.dp, bottom = 14.dp, top = 4.dp)) {
                            AnimatedContent(
                                targetState = state.revealed,
                                transitionSpec = {
                                    (fadeIn(tween(160, delayMillis = 30)) + slideInVertically(tween(200, easing = Easing.Emphasized)) { if (reduced) 0 else it / 12 }) togetherWith
                                        (fadeOut(tween(100)) + slideOutVertically(tween(140)) { if (reduced) 0 else it / 12 })
                                },
                                label = "actions",
                            ) { revealed ->
                                if (!revealed) {
                                    PrimaryButton("Показать ответ", onClick = { feedback.reveal(); vm.reveal() })
                                } else {
                                    Column {
                                        if (!compact) com.marat.albion.ui.components.CoachMark("study.swipe",
                                            "Смахните карточку: вправо — «Хорошо», влево — «Снова». Или выберите оценку ниже")
                                        RatingBar(current.intervals, rate)
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }
    }
}

/** Reserve reading space; tall actions scroll independently instead of squeezing the card away. */
@Composable
private fun StudyWorkspace(
    modifier: Modifier = Modifier,
    card: @Composable (Modifier) -> Unit,
    actions: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxActionsHeight = maxHeight / 2
        when {
            maxWidth >= 560.dp && maxHeight < 360.dp -> Row(Modifier.fillMaxSize()) {
                card(Modifier.weight(1f).fillMaxHeight())
                actions(Modifier.width(216.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
            maxHeight < 240.dp -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                card(Modifier.fillMaxWidth().height(280.dp))
                actions(Modifier.fillMaxWidth())
            }
            else -> Column(Modifier.fillMaxSize()) {
                card(Modifier.weight(1f).fillMaxWidth())
                actions(Modifier.fillMaxWidth().heightIn(max = maxActionsHeight).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun studyTitle(source: StudySource, collectionName: String?, state: StudyState): String {
    // Вне словаря подписываем словарь текущей карточки: от него зависит, что вспоминать.
    val deck = state.current?.let { Deck.byId(it.card.deck)?.title }
    return when (source) {
        is StudySource.Dictionary -> source.deck.title
        is StudySource.Collection -> listOfNotNull(collectionName ?: "Коллекция", deck).joinToString(" · ")
        StudySource.Today -> listOfNotNull("Сегодня", deck).joinToString(" · ")
    }
}

@Composable
private fun TopRow(title: String, state: StudyState, compact: Boolean, onClose: () -> Unit, onUndo: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            com.marat.albion.ui.components.RoundIconButton(AlbionIcons.Close, "Закрыть", onClose, spin = 90f)
            Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (compact) Text(
                        if (state.answerLimit != null) "${state.answered} / ${state.targetAnswers} ответов"
                        else "Осталось: ${state.remaining + if (state.current != null) 1 else 0}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            com.marat.albion.ui.components.FeatureHelp("study.ratings", "Как оценить ответ",
                "Снова — не вспомнили.\n\nТрудно — вспомнили с усилием.\n\nХорошо — вспомнили уверенно.\n\nЛегко — вспомнили сразу. Для многозначного слова оцените знание всех значений.\n\nПосле раскрытия можно смахнуть карточку: вправо — «Хорошо», влево — «Снова».", showOnFirstUse = false, filled = false)
            com.marat.albion.ui.components.RoundIconButton(AlbionIcons.Undo, "Отменить ответ", onUndo, spin = -60f, enabled = state.canUndo)
        }
        if (!compact && state.answerLimit != null) {
            Column(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Короткое занятие", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${state.answered} / ${state.targetAnswers} ответов", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        } else if (!compact) Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueueCount(state.newCount + if (state.current?.isNew == true) 1 else 0, "новые", Modifier.weight(1f))
            QueueCount(state.learningCount + if (state.current?.fromLearning == true) 1 else 0, "учу", Modifier.weight(1f))
            QueueCount(
                state.reviewCount + if (state.current?.let { !it.isNew && !it.fromLearning } == true) 1 else 0,
                "повтор", Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QueueCount(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.labelLarge, color = if (value > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Края карточек «под» текущей — ощущение колоды, тает по мере прохождения. */
@Composable
private fun CardStackShadow(remaining: Int) {
    val layers = remaining.coerceIn(0, 2)
    for (k in layers downTo 1) {
        val inset = (k * 10).dp
        Box(
            Modifier.fillMaxSize()
                .padding(horizontal = inset)
                .offset(y = (k * 8).dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f - k * 0.35f), MaterialTheme.shapes.extraLarge),
        )
    }
}

/** Карточка: 3D-переворот по оси Y с лёгким «проседанием» в середине поворота. */
@Composable
private fun FlipCard(
    revealed: Boolean,
    onReveal: () -> Unit,
    showHint: Boolean,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = if (reduced) androidx.compose.animation.core.snap() else tween(480, easing = Easing.Standard),
        label = "flip",
    )
    val dip = if (reduced) 1f else 1f - 0.06f * sin((rotation / 180f) * PI).toFloat()
    val shape = MaterialTheme.shapes.extraLarge

    Surface(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                rotationY = if (reduced) 0f else rotation
                scaleX = dip; scaleY = dip
                cameraDistance = 16f * density
            }
            .clip(shape)
            .clickable(enabled = !revealed, interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.foundation.LocalIndication.current, onClick = onReveal),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(.75.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize()
            ) {
            if (rotation <= 90f) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().padding(vertical = if (showHint) 24.dp else 8.dp), contentAlignment = Alignment.Center) { front() }
                    }
                    if (showHint) Text("Нажмите, чтобы перевернуть", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AlbionFonts.Reading, fontStyle = FontStyle.Italic),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val scroll = rememberScrollState()
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = if (reduced) 0f else 180f }) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 28.dp)) { back() }
                    if (scroll.canScrollForward) Text("Ещё ниже ↓", Modifier.align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** «Хорошо» — основное действие; остальные оценки сохраняют равную область нажатия. */
@Composable
private fun RatingBar(intervals: Map<Rating, String>, onRate: (Rating) -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelWidth = with(LocalDensity.current) {
        Rating.entries.maxOf { measurer.measure(it.label, MaterialTheme.typography.labelLarge).size.width }.toDp() + 12.dp
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            labelWidth * 4 + 24.dp <= maxWidth -> 4
            labelWidth * 2 + 8.dp <= maxWidth -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Rating.entries.chunked(columns).forEach { ratings ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ratings.forEach { r ->
                        RatingButton(r, Rating.entries.indexOf(r), intervals[r].orEmpty(), onRate, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(r: Rating, index: Int, interval: String, onRate: (Rating) -> Unit, modifier: Modifier = Modifier) {

    val (bg, fg, border) = when (r) {
        Rating.Again -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        Rating.Hard -> Triple(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.controlBorder)
        Rating.Good -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
        Rating.Easy -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
    }
    val interaction = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) delay(index * 20L)
        appear.animateTo(1f, tween(if (reduced) 0 else 180, easing = Easing.EmphasizedDecelerate))
    }
    Column(
        modifier.fillMaxHeight().heightIn(min = 64.dp)
            .graphicsLayer { alpha = if (reduced) 1f else appear.value; translationY = if (reduced) 0f else (1f - appear.value) * 12f }
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .border(1.dp, border, MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { onRate(r) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(r.label, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(interval, style = MaterialTheme.typography.labelSmall,
            color = if (r == Rating.Good) MaterialTheme.colorScheme.onPrimary else fg.copy(alpha = 0.85f), textAlign = TextAlign.Center)
    }
}

/** Summary of the completed study session. */
@Composable
private fun Finished(state: StudyState, onDone: () -> Unit, onUndo: () -> Unit) {
    val feedback = LocalFeedback.current
    LaunchedEffect(Unit) { if (state.answered > 0) feedback.complete() }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {

        Spacer(Modifier.height(24.dp))
        Text(if (state.answered == 0) "Всё повторено." else "Отличная работа.", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Text(
            if (state.answered == 0) "Сейчас здесь нечего повторять."
            else if (state.answerLimit != null) "Короткое занятие завершено. Прогресс сохранён — к оставшимся карточкам можно вернуться позже."
            else "Занятие завершено. Следующие карточки FSRS покажет точно в срок.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.answered > 0) {
            Spacer(Modifier.height(28.dp))
            val metrics = listOf(
                Triple(state.answered, pluralRu(state.answered, "ответ", "ответа", "ответов"), ""),
                Triple((state.answered - state.again) * 100 / state.answered, "вспомнили", "%"),
                (state.elapsedMs / 60000).toInt().coerceAtLeast(1).let { Triple(it, pluralRu(it, "минута", "минуты", "минут"), "") },
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.forEach { (value, label, suffix) -> StatPill(value, label, Modifier.fillMaxWidth(), suffix) }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { (value, label, suffix) -> StatPill(value, label, Modifier.weight(1f), suffix) }
                }
            }
        }
        if (state.learningCount > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Ещё ${state.learningCount} — на коротких шагах. Вернитесь через несколько минут.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(40.dp))
        PrimaryButton("Готово", onClick = onDone)
        if (state.canUndo) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Отменить последний ответ", onClick = onUndo)
        }
    }
    }
}

/**
 * Свайп оценки: после переворота карточку можно смахнуть вправо («Хорошо») или влево («Снова»).
 * Карточка наклоняется за пальцем, проступает подпись; короткий жест возвращает её на место.
 */
@Composable
internal fun SwipeToRate(enabled: Boolean, onRate: (Rating) -> Unit, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val progress = (offset.value / (width * 0.35f)).coerceIn(-1f, 1f)
    LaunchedEffect(enabled, reduced) { offset.snapTo(0f) }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationX = offset.value
                rotationZ = if (reduced) 0f else progress * 6f
            }
            .pointerInput(enabled, reduced) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val rating = when {
                            offset.value > width * 0.3f -> Rating.Good
                            offset.value < -width * 0.3f -> Rating.Again
                            else -> null
                        }
                        scope.launch {
                            if (rating == null) {
                                if (reduced) offset.snapTo(0f) else offset.animateTo(0f, com.marat.albion.ui.theme.Springs.bouncy())
                            }
                            else if (reduced) { offset.snapTo(0f); onRate(rating) }
                            else {
                                offset.animateTo(if (rating == Rating.Good) width * 1.4f else -width * 1.4f,
                                    androidx.compose.animation.core.tween(180))
                                onRate(rating)
                                offset.snapTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { if (reduced) offset.snapTo(0f) else offset.animateTo(0f, com.marat.albion.ui.theme.Springs.bouncy()) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + drag) }
                }
            },
    ) {
        content()
        if (progress != 0f) {
            val good = progress > 0
            Text(
                if (good) "Хорошо" else "Снова",
                Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .graphicsLayer { alpha = kotlin.math.abs(progress); rotationZ = if (reduced) 0f else if (good) -8f else 8f }
                    .background(if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}
