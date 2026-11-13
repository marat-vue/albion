package com.marat.albion.ui.conversation

import androidx.compose.foundation.shape.CircleShape
import com.marat.albion.ui.theme.Capsule

import com.marat.albion.ui.components.TextButton
import com.marat.albion.ui.components.PaneDropdownMenu as DropdownMenu
import com.marat.albion.ui.components.PaneAlertDialog as AlertDialog
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.theme.quietItem
import com.marat.albion.ui.theme.quietContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.conversation.Scenario
import com.marat.albion.data.db.ConversationMessageEntity
import com.marat.albion.ui.components.*
import com.marat.albion.ui.games.rememberMicAccess
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.appear
import com.marat.albion.ui.theme.controlBorder
import androidx.compose.ui.focus.onFocusChanged

/** Беседа: реплики, потоковый ответ модели, разбор ошибок под фразами ученика, ввод текстом и голосом. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(id: Long, navigator: Navigator) {
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    val c = LocalAppContainer.current
    val context = LocalContext.current.applicationContext
    val vm = viewModel(key = "chat-$id") { ChatViewModel(c, context, id) }
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val model by c.conversations.status.collectAsStateWithLifecycle()
    val speechModel by c.speech.models.state.collectAsStateWithLifecycle()
    val recording by vm.recorder.recording.collectAsStateWithLifecycle()
    val level by vm.recorder.level.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf<SetupSheet?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    val micAccess = rememberMicAccess(onDenied = { micDenied = true })
    val list = rememberLazyListState()

    val items = messages.orEmpty()
    // Новые реплики и растущий ответ держат ленту внизу, если ученик не листает историю.
    LaunchedEffect(items.size, vm.streaming?.length, reduced) {
        if (list.firstVisibleItemIndex <= 1) { if (reduced) list.scrollToItem(0) else list.animateScrollToItem(0) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        val maxInputHeight = (maxHeight / 3).coerceIn(52.dp, 160.dp)
        Column(Modifier.fillMaxSize()) {
            TopBar(navigator::back, conversation?.title) {
                Box {
                    RoundIconButton(AlbionIcons.MoreHoriz, "Действия с беседой", { menu = true }, filled = true)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Другой ответ") }, { menu = false; vm.regenerate() },
                            enabled = !vm.busy && items.lastOrNull()?.fromUser == false && items.any { it.fromUser },
                            leadingIcon = { Icon(AlbionIcons.Undo, null) })
                        DropdownMenuItem({ Text("Переименовать") }, { menu = false; renaming = true },
                            leadingIcon = { Icon(AlbionIcons.BorderColor, null) })
                        DropdownMenuItem({ Text("Удалить беседу") }, { menu = false; deleting = true },
                            leadingIcon = { Icon(AlbionIcons.Delete, null) },
                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error))
                    }
                }
            }
            if (!WindowInsets.isImeVisible) conversation?.let {
                ChatContext(Scenario.of(it.scenario).title, it.level,
                    if (vm.compacting) "Сжимаю начало беседы…" else null)
            }
            Hairline()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                vm.streaming?.let { text ->
                    item(key = "streaming") { PartnerBubble(text, quietItem()) { if (text.isEmpty()) TypingDots() } }
                }
                val last = items.lastOrNull()
                if (!vm.busy && last != null && last.fromUser && vm.error == null) item(key = "retry") {
                    Row(quietItem(Modifier.fillMaxWidth()), horizontalArrangement = Arrangement.Start) {
                        GhostButton("Получить ответ", vm::retry, icon = AlbionIcons.ArrowForward)
                    }
                }
                items(items.asReversed(), key = { it.id }) { message ->
                    Column(quietItem(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (message.fromUser) UserBubble(message, vm.checking[message.id] == true) { vm.check(message.id) }
                        else PartnerBubble(message.text) {
                            val translating = vm.translating[message.id] == true
                            if (vm.shownTranslations[message.id] == true) {
                                if (translating) Text("Перевожу…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                message.translation?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ vm.toggleTranslation(message) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text(if (vm.shownTranslations[message.id] == true) "Скрыть перевод" else "Перевод",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        // Граница пересказа: реплики выше модель помнит только в кратком изложении.
                        conversation?.takeIf { it.summaryUpTo == message.id && it.summary.isNotBlank() }?.let { SummaryMarker(it.summary) }
                    }
                }
            }
            vm.error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (items.lastOrNull()?.fromUser == true && !vm.busy) TextButton(vm::retry) { Text("Повторить") }
                    RoundIconButton(AlbionIcons.Close, "Скрыть ошибку", vm::dismissError, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (vm.hint != null || vm.hintLoading) HintCard(vm.hint, vm::useHint, vm::dismissHint)
            if (micDenied) Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
                Text("Нет доступа к микрофону.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppSettingsButton()
            }
            if (!model.ready) ModelMissingBar("Для ответов нужен ключ Groq", "Добавить") { setup = SetupSheet.Ai }
            ChatComposer(
                draft = vm.draft, onDraft = vm::edit, busy = vm.busy, recording = recording, level = level,
                transcribing = vm.transcribing, hintLoading = vm.hintLoading, enabled = model.ready,
                onSend = vm::send, onStop = vm::stop, onHint = vm::requestHint,
                onMic = {
                    when {
                        recording -> vm.stopRecording()
                        !speechModel.ready -> setup = SetupSheet.Whisper
                        else -> micAccess { micDenied = false; if (!vm.startRecording()) micDenied = true }
                    }
                }, maxInputHeight = maxInputHeight,
            )
        }
    }

    setup?.let { sheet ->
        ScrollableSheet(if (sheet == SetupSheet.Ai) "Собеседник · Groq" else "Голосовой ввод", { setup = null }) {
            if (sheet == SetupSheet.Ai) AiControls() else SpeechModelControls()
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(conversation?.title.orEmpty()) }
        AlertDialog({ renaming = false }, title = { Text("Название беседы") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton({ renaming = false; vm.rename(title) }) { Text("Сохранить") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Отмена") } })
    }
    if (deleting) DestructiveConfirmationDialog(
        title = "Удалить беседу?",
        message = "Все реплики и разборы этой беседы будут удалены. Отменить удаление нельзя.",
        onConfirm = { deleting = false; vm.delete(navigator::back) },
        onDismiss = { deleting = false },
    )
}

private enum class SetupSheet { Ai, Whisper }

@Composable
internal fun ChatContext(scenario: String, level: String, status: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(scenario)
            Chip(level, Modifier.width(IntrinsicSize.Max))
        }
        if (status != null) Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Реплика собеседника: слева, на поверхности с тонкой рамкой. */
@Composable
internal fun PartnerBubble(text: String, modifier: Modifier = Modifier, footer: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth(0.88f).quietContentSize()) {
        Column(Modifier.clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyLarge)
            footer()
        }
    }
}


/** Реплика ученика: справа, инверсная; под ней — разбор ошибок. */
@Composable
private fun UserBubble(message: ConversationMessageEntity, checking: Boolean, modifier: Modifier = Modifier, onCheck: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(0.88f), horizontalArrangement = Arrangement.End) {
            Row(Modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primary)
                .border(.75.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = .35f), MaterialTheme.shapes.large)
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                if (message.voice) {
                    Icon(AlbionIcons.Mic, "Голосом", Modifier.padding(top = 3.dp, end = 8.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        val correction = message.correction
        when {
            checking -> SignalLabel("Проверяю фразу…")
            correction == null -> TextButton(onCheck, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Проверить фразу", style = MaterialTheme.typography.labelMedium)
            }
            correction.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AlbionIcons.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
                Text("Без ошибок", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> CorrectionCard(correction, Modifier.fillMaxWidth(0.88f).appear())
        }
    }
}

@Composable
internal fun CorrectionCard(correction: String, modifier: Modifier = Modifier) {
    val corrected = correction.substringBefore('\n')
    val why = correction.substringAfter('\n', "").trim()
    val scheme = MaterialTheme.colorScheme
    Column(modifier.clip(MaterialTheme.shapes.medium).background(scheme.tertiaryContainer)
        .border(.75.dp, scheme.tertiary.copy(alpha = .45f), MaterialTheme.shapes.medium)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("Лучше сказать", color = scheme.onTertiaryContainer)
        Text(corrected, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = scheme.onTertiaryContainer)
        if (why.isNotEmpty()) Text(why, style = MaterialTheme.typography.bodySmall, color = scheme.onTertiaryContainer)
    }
}

/** Отметка сжатия: по нажатию видно, что модель помнит о начале беседы. */
@Composable
private fun SummaryMarker(summary: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClickLabel = if (open) "Скрыть пересказ" else "Показать пересказ") { open = !open }
        .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hairline(Modifier.weight(1f))
            SignalLabel(if (open) "Модель помнит кратко" else "Выше — пересказ для модели", Modifier.padding(horizontal = 12.dp))
            Hairline(Modifier.weight(1f))
        }
        if (open) Text(summary, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Три точки, пока модель читает беседу и готовит первые слова. */
@Composable
private fun TypingDots() {
    val reduced = LocalReducedMotion.current
    Row(Modifier.height(22.dp).semantics { contentDescription = "Собеседник печатает" },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha = if (reduced) .6f else {
                val transition = rememberInfiniteTransition(label = "typing")
                transition.animateFloat(0.25f, 1f, infiniteRepeatable(
                    keyframes { durationMillis = 1200; 0.25f at 0; 1f at 200 + i * 150; 0.25f at 700 + i * 150 }), label = "dot$i").value
            }
            Box(Modifier.size(7.dp).alpha(if (reduced) 0.6f else alpha).background(MaterialTheme.colorScheme.onSurface, CircleShape))
        }
    }
}

@Composable
private fun HintCard(hint: String?, onUse: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp)
        .border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        .clip(MaterialTheme.shapes.medium).clickable(enabled = hint != null, onClick = onUse)
        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AlbionIcons.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Eyebrow(if (hint == null) "Подбираю вариант…" else "Можно сказать · нажмите, чтобы вставить")
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        RoundIconButton(AlbionIcons.Close, "Скрыть подсказку", onDismiss)
    }
}

@Composable
internal fun ModelMissingBar(text: String, action: String = "Установить", onSetup: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AlbionIcons.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onSetup) { Text(action) }
    }
}

/** Поле ввода: текст, подсказка, микрофон; при записи — уровень голоса и «стоп». */
@Composable
internal fun ChatComposer(
    draft: String, onDraft: (String) -> Unit, busy: Boolean, recording: Boolean, level: Float, transcribing: Boolean,
    hintLoading: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit, onHint: () -> Unit, onMic: () -> Unit,
    maxInputHeight: Dp = 160.dp,
) {
    val scheme = MaterialTheme.colorScheme
    var toolsOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val minInputHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } + 24.dp
    BoxWithConstraints(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding()) {
        val horizontalPadding = if (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f) 12.dp else gutter - 4.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                RoundIconButton(AlbionIcons.Add, "Действия сообщения", { toolsOpen = true }, enabled = enabled && !busy && !recording && !transcribing)
                DropdownMenu(toolsOpen, { toolsOpen = false }) {
                    DropdownMenuItem(text = { Text("Подсказать ответ") }, onClick = { toolsOpen = false; onHint() },
                        enabled = !hintLoading, leadingIcon = { Icon(AlbionIcons.Lightbulb, null) })
                    DropdownMenuItem(text = { Text("Сказать голосом") }, onClick = { toolsOpen = false; onMic() },
                        leadingIcon = { Icon(AlbionIcons.Mic, null) })
                }
            }
            Box(Modifier.weight(1f).heightIn(min = maxOf(52.dp, minInputHeight), max = maxOf(maxInputHeight, minInputHeight, 52.dp)).clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainerLow)
                .border(if (focused) 2.dp else 1.dp, if (focused) scheme.primary else scheme.controlBorder, MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart) {
                when {
                    recording -> RecordingLevel(level)
                    transcribing -> Text("Распознаю…", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> {
                        if (draft.isEmpty()) Text("Ваш ответ…", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(draft, onDraft, Modifier.fillMaxWidth().semantics { contentDescription = "Ваш ответ" }.onFocusChanged { focused = it.isFocused }, enabled = enabled,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface), maxLines = 6,
                            cursorBrush = SolidColor(scheme.onSurface),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                busy -> ActionButton(AlbionIcons.Stop, "Остановить ответ", onStop)
                draft.isNotBlank() && !recording -> ActionButton(AlbionIcons.ArrowForward, "Отправить", onSend, enabled = enabled)
                else -> ActionButton(if (recording) AlbionIcons.Stop else AlbionIcons.Mic,
                    if (recording) "Закончить запись" else "Сказать голосом", onMic, enabled = enabled && !transcribing, pulse = recording)
            }
        }
    }
}

/** Главное действие поля ввода: инверсный круг; во время записи мягко «дышит». */
@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit,
                         enabled: Boolean = true, pulse: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val feedback = com.marat.albion.ui.theme.LocalFeedback.current
    val scale = if (pulse && !reduced) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(1f, 1.08f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale").value
    } else 1f
    Box(Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
        .background(if (enabled) scheme.primary else scheme.surfaceContainerHigh)
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = description) { feedback.selection(); onClick() }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant)
    }
}

/** Полоски уровня громкости: видно, что микрофон слышит. Пауза в 2,5 с завершает запись. */
@Composable
private fun RecordingLevel(level: Float) {
    if (LocalReducedMotion.current) {
        Text("Запись…", Modifier.clearAndSetSemantics { contentDescription = "Идёт запись. Пауза завершит её" },
            style = MaterialTheme.typography.bodyLarge)
        return
    }
    val history = remember { mutableStateListOf<Float>().apply { repeat(28) { add(0f) } } }
    LaunchedEffect(level) { history.removeAt(0); history.add(level) }
    Row(Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = "Идёт запись. Пауза завершит её" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        history.forEach { value ->
            Box(Modifier.weight(1f).fillMaxHeight(0.15f + 0.85f * value).background(MaterialTheme.colorScheme.onSurface, Capsule))
        }
    }
}
