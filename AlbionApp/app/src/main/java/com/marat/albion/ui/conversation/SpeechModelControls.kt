package com.marat.albion.ui.conversation

import com.marat.albion.ui.components.TextButton
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.components.QuietLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.speech.WHISPER_MODELS
import com.marat.albion.data.speech.ModelState
import com.marat.albion.ui.components.*
import com.marat.albion.ui.settings.SwitchSetting
import kotlinx.coroutines.launch

/** Установка Whisper для голосового ввода: выбор размера, загрузка с проверкой, удаление. */
@Composable
fun SpeechModelControls() {
    val speech = LocalAppContainer.current.speech
    val models = speech.models
    val status by models.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Whisper превращает вашу речь в текст прямо на телефоне. Запись не покидает устройство, интернет нужен только для загрузки.",
            style = MaterialTheme.typography.bodyMedium)
        if (status.phase == ModelState.Unavailable) {
            FieldError(status.message)
            return@Column
        }
        if (!status.busy) {
            val ids = WHISPER_MODELS.map { it.id }
            OptionPicker("Модель распознавания", WHISPER_MODELS.map { it.title }, ids.indexOf(status.selected),
                { scope.launch { models.select(ids[it]) } })
            Text(if (status.selected == "small.en") "Точнее понимает акцент и быструю речь. Фраза распознаётся за 2–5 секунд."
                else "Быстрая и лёгкая. Подойдёт, если вы говорите чётко или телефон не самый мощный.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FieldError(status.message.takeIf { status.phase != ModelState.Downloading })
        when {
            status.busy -> {
                Text(status.summary, style = MaterialTheme.typography.bodyMedium)
                if (status.phase == ModelState.Downloading) {
                    ThinProgress(if (status.total > 0) status.downloaded.toFloat() / status.total else 0f)
                    Text("${status.downloaded / 1_000_000} / ${status.total / 1_000_000} МБ", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                TextButton(onClick = { scope.launch { models.cancel() } }) { Text("Отменить") }
            }
            status.ready -> {
                Text("Модель установлена. Голосовой ввод работает офлайн.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { deleting = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить выбранную модель") }
            }
            else -> {
                SwitchSetting("Загрузка по мобильной сети", status.allowMobile) { scope.launch { models.setAllowMobile(it) } }
                PrimaryButton(if (status.phase == ModelState.Failed) "Повторить загрузку" else "Загрузить модель",
                    { scope.launch { models.downloadSelected() } }, icon = AlbionIcons.Mic)
            }
        }
    }
    if (deleting) DestructiveConfirmationDialog(
        title = "Удалить модель распознавания?",
        message = "Беседы сохранятся, писать сообщения можно и дальше. Для голосового ввода модель потребуется установить снова.",
        onConfirm = { deleting = false; speech.releaseMemory(); scope.launch { models.deleteSelected() } },
        onDismiss = { deleting = false },
    )
}
