package com.marat.albion.ui.conversation

import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.theme.quietItem
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.conversation.CONVERSATION_LEVELS
import com.marat.albion.data.conversation.Scenario
import com.marat.albion.data.db.ConversationSummary
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.ChatRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.theme.appear
import kotlinx.coroutines.launch

/**
 * Разговорная практика: новая беседа (уровень + ситуация), история бесед и модели.
 * Собеседник — ИИ в облаке Groq (бесплатный ключ, из России через VPN); Whisper распознаёт речь на телефоне.
 */
@Composable
fun ConversationsScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("conversation", Context.MODE_PRIVATE) }
    var level by remember { mutableStateOf(prefs.getString("level", "B1") ?: "B1") }
    val history by remember { c.conversations.summaries() }.collectAsStateWithLifecycle(null)
    val model by c.conversations.status.collectAsStateWithLifecycle()
    val speech by c.speech.models.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    fun start(scenario: Scenario) = scope.launch { navigator.open(ChatRoute(c.conversations.create(scenario, level))) }

    Column(Modifier.fillMaxSize()) {
        TopBar(navigator::back, "Разговор") {
            FeatureHelp("conversation", "Как устроена беседа",
                "Выберите уровень и ситуацию — собеседник начнёт разговор. Отвечайте текстом или голосом: " +
                    "микрофон записывает, пока вы говорите, а пауза в пару секунд завершает запись.\n\n" +
                    "После каждой вашей реплики модель проверяет её и показывает, как сказать лучше. Под ответами собеседника — " +
                    "озвучка и перевод, лампочка подскажет, что ответить.\n\n" +
                    "Собеседник работает через Groq: нужен интернет и включённый VPN. Голос распознаётся на телефоне. " +
                    "Беседы сохраняются в истории.")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 32.dp + com.marat.albion.ui.components.bottomChrome),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BoundVolume(Modifier.fillMaxWidth().appear(1)) {
                    Eyebrow("ИИ Groq · нужен VPN", color = androidx.compose.material3.LocalContentColor.current)
                    Text("Говорите по-английски\nс собеседником-ИИ", style = MaterialTheme.typography.headlineSmall)
                    Text("Пишите или говорите — модель ответит, поправит ошибки и подскажет. Бесплатно, в пределах лимитов Groq.",
                        style = MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.LocalContentColor.current)
                    SegmentedPicker(CONVERSATION_LEVELS, CONVERSATION_LEVELS.indexOf(level).coerceAtLeast(0), {
                        level = CONVERSATION_LEVELS[it]
                        prefs.edit().putString("level", level).apply()
                    }, inverse = true)
                    PrimaryButton("Свободная беседа", { start(Scenario.FREE) }, trailingIcon = AlbionIcons.ArrowForward,
                        inverse = true, enabled = model.ready)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.appear(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.ready) VpnNotice()
                    Disclosure("Собеседник · Groq", model.summary, initiallyExpanded = !model.ready,
                        icon = if (model.ready) AlbionIcons.Check else AlbionIcons.CloudOff) {
                        AiControls()
                    }
                    if (!speech.ready) Disclosure("Голосовой ввод · Whisper", speech.summary, initiallyExpanded = model.ready, icon = AlbionIcons.Mic) {
                        SpeechModelControls()
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { ModuleHeading("01", "Ситуации", (Scenario.entries.size - 1).toString()) }
            items(Scenario.entries.filter { it != Scenario.FREE }, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { scenario ->
                // Строка как в «Практике»: иконка слева, справа название и пояснение.
                Panel(quietItem(Modifier.fillMaxWidth()), onClick = if (model.ready) ({ start(scenario) }) else null,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center) {
                            Icon(scenario.icon, null, Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(scenario.title, style = MaterialTheme.typography.titleMedium)
                            Text(scenario.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (model.ready) {
                            Spacer(Modifier.width(8.dp))
                            Icon(AlbionIcons.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ModuleHeading("02", "История", history?.size?.takeIf { it > 0 }?.toString())
            }
            val conversations = history
            when {
                conversations == null -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingState("Загружаю беседы…") }
                conversations.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("Бесед пока нет", "Выберите ситуацию выше — разговор сохранится здесь, и к нему можно вернуться.")
                }
                else -> items(conversations, key = { "c${it.conversation.id}" }, span = { GridItemSpan(maxLineSpan) }) { summary ->
                    HistoryRow(summary, quietItem(), onOpen = { navigator.open(ChatRoute(summary.conversation.id)) },
                        onDelete = { deleting = summary })
                }
            }
        }
    }
    deleting?.let { summary ->
        DestructiveConfirmationDialog(
            title = "Удалить беседу?",
            message = "«${summary.conversation.title}», все её реплики и разборы будут удалены. Отменить удаление нельзя.",
            onConfirm = { deleting = null; scope.launch { c.conversations.delete(summary.conversation.id) } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun HistoryRow(summary: ConversationSummary, modifier: Modifier, onOpen: () -> Unit, onDelete: () -> Unit) {
    val conversation = summary.conversation
    Panel(modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                summary.preview?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(listOfNotNull(
                    conversation.level,
                    DateUtils.getRelativeTimeSpanString(conversation.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    summary.turns.takeIf { it > 0 }?.let { "реплик: $it" },
                    summary.corrections.takeIf { it > 0 }?.let { "исправлений: $it" },
                ).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RoundIconButton(AlbionIcons.Delete, "Удалить беседу «${conversation.title}»", onDelete,
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Знак ситуации: раньше у всех семи была одна и та же иконка. */
private val Scenario.icon: androidx.compose.ui.graphics.vector.ImageVector get() = when (this) {
    Scenario.FREE -> AlbionIcons.Chat
    Scenario.DAY -> AlbionIcons.Sun
    Scenario.CAFE -> AlbionIcons.Coffee
    Scenario.HOTEL -> AlbionIcons.Bed
    Scenario.INTERVIEW -> AlbionIcons.Briefcase
    Scenario.DOCTOR -> AlbionIcons.Health
    Scenario.SHOP -> AlbionIcons.Bag
    Scenario.OPINION -> AlbionIcons.Debate
}
