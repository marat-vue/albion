package com.marat.albion.ui.settings

import com.marat.albion.ui.components.TextButton
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.theme.selectedContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import com.marat.albion.ui.components.SegmentedPicker
import com.marat.albion.data.settings.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.ImportStatus
import com.marat.albion.data.settings.NewCardOrder
import com.marat.albion.data.settings.Settings
import com.marat.albion.ui.components.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val s by c.settings.settings.collectAsStateWithLifecycle(Settings())
    val status by c.importer.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repo = c.settings
    var reset by rememberSaveable { mutableIntStateOf(0) }
    var resetBusy by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status) { version = repo.contentVersion() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack, "Настройки")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = gutter).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Оформление", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            SegmentedPicker(ThemeMode.entries.map { it.title }, s.themeMode.ordinal, { scope.launch { repo.setThemeMode(ThemeMode.entries[it]) } })
            Spacer(Modifier.height(16.dp))
            Disclosure("Занятия и цель", "${s.dailyGoal} ответов в день", icon = com.marat.albion.ui.components.AlbionIcons.TaskAlt) {
                GoalReminderSection(s)
                SliderSetting("Новых карточек в день", "${s.newPerDay}",
                    "Общий лимит для коллекций и «Сегодня»; словарь целиком — отдельно. Если удержание падает или впереди много повторений, новых будет меньше.",
                    s.newPerDay.toFloat(), 0f..100f, 19) { scope.launch { repo.setNewPerDay((it / 5).roundToInt() * 5) } }
                SliderSetting("Повторений в день", "${s.reviewsPerDay}", "Для каждого словаря и коллекции отдельно.",
                    s.reviewsPerDay.toFloat(), 50f..1000f, 18) { scope.launch { repo.setReviewsPerDay((it / 50).roundToInt() * 50) } }
                Disclosure("Порядок новых карточек", s.newOrder.title) {
                    Column(Modifier.selectableGroup()) { NewCardOrder.entries.forEach { order ->
                        RadioSettingOption(order.title, s.newOrder == order) { scope.launch { repo.setNewOrder(order) } }
                    } }
                }
            }
            Disclosure("Карточки и озвучка", "Перевод, транскрипция и воспроизведение", icon = com.marat.albion.ui.components.AlbionIcons.Layers) {
                SwitchSetting("Озвучивать слово при показе", s.autoplayFront) { scope.launch { repo.setAutoplayFront(it) } }
                SwitchSetting("Озвучивать при перевороте", s.autoplayBack) { scope.launch { repo.setAutoplayBack(it) } }
                SwitchSetting("Транскрипция на лицевой стороне", s.showIpaOnFront) { scope.launch { repo.setShowIpaOnFront(it) } }
                SwitchSetting("Русский перевод на обороте карточек", s.showRussian) { scope.launch { repo.setShowRussian(it) } }
                SwitchSetting("Русский перевод в словарном списке", s.browseShowRussian) { scope.launch { repo.setBrowseShowRussian(it) } }
                SliderSetting("Громкость озвучки", "${(s.playbackVolume * 100).roundToInt()}%",
                    "Относительно громкости медиа на телефоне.", s.playbackVolume, 0f..1f, 9) {
                    c.audio.setVolume(it); scope.launch { repo.setPlaybackVolume(it) }
                }
                SliderSetting("Скорость озвучки", "×%.1f".format(s.playbackSpeed), null, s.playbackSpeed, 0.5f..1.5f, 9) {
                    c.audio.setSpeed(it); scope.launch { repo.setPlaybackSpeed(it) }
                }
                ActionRow("Прослушать пример") { c.audio.speak("A little, every day.", "settings-preview") }
            }
            Disclosure("Чтение и учебник", "Размер текста, интервал и поля", icon = com.marat.albion.ui.components.AlbionIcons.TextFields) {
                SliderSetting("Текст учебника", "${(s.grammarTextScale * 100).roundToInt()}%", null,
                    s.grammarTextScale, 0.85f..1.4f, 10) { scope.launch { repo.setGrammarTextScale(it) } }
                com.marat.albion.ui.grammar.ProvideMdEnv(com.marat.albion.ui.grammar.MdEnv(s.grammarTextScale) {}) {
                    com.marat.albion.ui.grammar.MdBlockView(com.marat.albion.data.grammar.MdParagraph(
                        "Английский — маленькими шагами каждый день."))
                }
                SliderSetting("Текст книги", "${(s.readerTextScale * 100).roundToInt()}%", null,
                    s.readerTextScale, 0.8f..1.5f, 13) { scope.launch { repo.setReaderTextScale(it) } }
                SliderSetting("Межстрочный интервал", "${(s.readerLineSpacing * 100).roundToInt()}%", null,
                    s.readerLineSpacing, 0.85f..1.3f, 8) { scope.launch { repo.setReaderLineSpacing(it) } }
                SliderSetting("Поля книги", "${s.readerMargin} dp", null, s.readerMargin.toFloat(), 16f..40f, 11) {
                    scope.launch { repo.setReaderMargin(it.roundToInt()) }
                }
                Text("A little, every day. A good book is always a good companion.", Modifier.padding(horizontal = s.readerMargin.dp),
                    style = com.marat.albion.ui.theme.ReadingStyle.copy(fontSize = (18 * s.readerTextScale).sp, lineHeight = (29 * s.readerTextScale * s.readerLineSpacing).sp))
                SwitchSetting("Подчёркивать изучаемые слова в книге", s.readerMarkWords) { scope.launch { repo.setReaderMarkWords(it) } }
            }
            Disclosure("Звук и доступность", "Тактильный отклик и движение", icon = com.marat.albion.ui.components.AlbionIcons.VolumeUp) {
                SwitchSetting("Звук завершения занятия", s.interfaceSounds) { scope.launch { repo.setInterfaceSounds(it) } }
                Text("Тихое созвучие с учётом беззвучного режима телефона.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchSetting("Тактильный отклик", s.hapticsEnabled) { scope.launch { repo.setHapticsEnabled(it) } }
                SwitchSetting("Меньше движения", s.reduceMotion) { scope.launch { repo.setReduceMotion(it) } }
            }
            Disclosure("Перевод книг", "Google Translate · офлайн", icon = AlbionIcons.CloudOff) {
                com.marat.albion.ui.library.BookTranslationControls()
            }
            Disclosure("Расширенные настройки повторения", "Память, начало дня и персональная подстройка", icon = com.marat.albion.ui.components.AlbionIcons.Route) {
                SliderSetting("Желаемое удержание", "${(s.desiredRetention * 100).roundToInt()}%",
                    "Вероятность вспомнить карточку при показе. Рекомендуется 90%.", s.desiredRetention, 0.80f..0.97f, 16) {
                    scope.launch { repo.setRetention((it * 100).roundToInt() / 100f) }
                }
                SliderSetting("Начало учебного дня", "%02d:00".format(s.dayStartHour),
                    "Занятия до этого часа относятся к предыдущему дню.", s.dayStartHour.toFloat(), 0f..8f, 7) {
                    scope.launch { repo.setDayStartHour(it.roundToInt()) }
                }
                FsrsTuningBlock(s)
            }
            Disclosure("Материалы и данные", "Состояние курса и управление прогрессом", icon = com.marat.albion.ui.components.AlbionIcons.Inventory2) {
                when (val st = status) {
                    ImportStatus.Checking -> LoadingState("Проверяем материалы")
                    is ImportStatus.Importing -> LoadingState("Подготавливаем ${st.deck.title} · ${st.index}/${st.total}")
                    is ImportStatus.Failed -> {
                        Text("Не удалось подготовить материалы.", color = MaterialTheme.colorScheme.error)
                        ActionRow("Повторить подготовку") { c.appScope.launch { c.importer.importIfNeeded(force = true) } }
                    }
                    ImportStatus.Empty -> Text("Материалы недоступны. Установите полную версию приложения.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ImportStatus.Ready -> Text("Учебные материалы готовы", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                Disclosure("Сведения о материалах", "Версия курса и обслуживание") {
                    Text("Версия материалов: ${version ?: "не определена"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Материалы обновляются вместе с приложением. Прогресс сохраняется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionRow("Повторно проверить материалы") { c.appScope.launch { c.importer.importIfNeeded(force = true) } }
                }
                GrammarOwnAnswersSection()
                ActionRow("Сбросить только отметки прочитанного", destructive = true) { resetError = null; reset = 1 }
                ActionRow("Удалить весь прогресс учебника", destructive = true) { resetError = null; reset = 2 }
            }
            Text("Albion 1.0", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (reset != 0) DestructiveConfirmationDialog(
        title = if (reset == 1) "Сбросить отметки чтения?" else "Удалить весь прогресс учебника?",
        message = if (reset == 1) "Будут сняты только отметки «Прочитано». Освоение, закладки, результаты проверок и план повторения сохранятся. Отменить сброс нельзя."
            else "Будут удалены отметки чтения и освоения, закладки, последние результаты проверок и расписание повторения тем. Отменить удаление нельзя.",
        confirmLabel = if (reset == 1) "Сбросить" else "Удалить",
        busy = resetBusy,
        busyLabel = "Сбрасываем…",
        errorMessage = resetError,
        onConfirm = {
            resetError = null
            resetBusy = true
            scope.launch {
                try { if (reset == 1) c.grammar.resetReadMarks() else c.grammar.resetProgress(); reset = 0 }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { resetError = "Не удалось выполнить сброс. Попробуйте ещё раз." }
                finally { resetBusy = false }
            }
        },
        onDismiss = { reset = 0 },
    )
}

@Composable
private fun RadioSettingOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .selectionSurface(selected, MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Круглая отметка: на инверсной плашке выбора берёт цвет её содержимого.
        val mark = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.size(20.dp).border(1.5.dp, mark, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(mark, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SliderSetting(
    title: String,
    value: String,
    hint: String?,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, Capsule).padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        val step = (range.endInclusive - range.start) / (steps + 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = current > range.start, onClick = { onChange((current - step).coerceAtLeast(range.start)) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Уменьшить: $title" }) { Text("−") }
        ThinSlider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f).semantics { contentDescription = title; stateDescription = value },
        )
            TextButton(enabled = current < range.endInclusive, onClick = { onChange((current + step).coerceAtMost(range.endInclusive)) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Увеличить: $title" }) { Text("+") }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SwitchSetting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val feedback = com.marat.albion.ui.theme.LocalFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch) { feedback.selection(); onChange(it) }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        AlbionSwitch(checked)
    }
}

@Composable
internal fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        )
        Hairline()
    }
}

/** Журнал «Мой ответ тоже верен»: что дописать в альтернативы grammar_tasks.xlsx. */
@Composable
private fun GrammarOwnAnswersSection() {
    val c = LocalAppContainer.current
    val answers by c.settings.grammarOwnAnswers.collectAsStateWithLifecycle(emptyList())
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Disclosure("Ответы «Мой ответ тоже верен»", if (answers.isEmpty()) "Пока нет" else "${answers.size} в журнале") {
        Text(
            "Сюда попадают ответы, которые вы отметили верными в заданиях «напишите целиком». Их можно скопировать и дописать в альтернативы заданий.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        answers.asReversed().take(50).forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(entry.taskNumber, style = MaterialTheme.typography.labelMedium.copy(fontFamily = AlbionFonts.Tech),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (answers.size > 50) Text("…и ещё ${answers.size - 50} — все попадут в копию.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (answers.isNotEmpty()) {
            ActionRow(if (copied) "Скопировано" else "Скопировать список") {
                val text = com.marat.albion.data.grammar.OwnAnswerLog.export(answers)
                scope.launch {
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Albion", text)))
                    copied = true
                }
            }
            ActionRow("Очистить журнал", destructive = true) { scope.launch { c.settings.clearGrammarOwnAnswers(); copied = false } }
        }
    }
}
