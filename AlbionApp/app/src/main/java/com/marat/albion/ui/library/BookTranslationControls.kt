package com.marat.albion.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.library.BookTranslationPhase
import com.marat.albion.ui.components.FieldError
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.QuietLinearProgressIndicator
import com.marat.albion.ui.settings.SwitchSetting
import kotlinx.coroutines.launch

@Composable
fun BookTranslationControls() {
    val c = LocalAppContainer.current
    val translator = c.bookTranslator
    val status by translator.state.collectAsStateWithLifecycle()
    // Общая с моделью Qwen3.5 настройка загрузки по мобильной сети.
    val models = c.translator.models
    val network by models.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { translator.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Слова и выделенные фразы переводятся с английского на русский на устройстве. Языковую модель (около 30 МБ) нужно загрузить один раз; затем перевод работает без интернета. Словарь доступен и без неё.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Автоперевод: Google Translate · ML Kit", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (status.phase) {
            BookTranslationPhase.Checking, BookTranslationPhase.Downloading -> {
                Text(status.summary, style = MaterialTheme.typography.bodyMedium)
                if (status.phase == BookTranslationPhase.Downloading && status.wifiOnly) {
                    Text("Загрузка дождётся Wi-Fi или другой сети без тарификации.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QuietLinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                if (status.phase == BookTranslationPhase.Downloading) GhostButton("Отменить", translator::cancelInstall)
            }
            BookTranslationPhase.Ready -> Text("Перевод готов и доступен офлайн.", style = MaterialTheme.typography.bodyMedium)
            BookTranslationPhase.NotInstalled, BookTranslationPhase.Failed -> {
                FieldError(status.message)
                SwitchSetting("Загрузка по мобильной сети", network.allowMobile) { scope.launch { models.setAllowMobile(it) } }
                if (!network.allowMobile) Text("Загрузка дождётся Wi-Fi или другой сети без тарификации.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton(if (status.phase == BookTranslationPhase.Failed) "Повторить загрузку" else "Загрузить модель перевода",
                    { translator.install(network.allowMobile) })
            }
        }
    }
}
