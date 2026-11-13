package com.marat.albion.ui.conversation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.ai.AiException
import com.marat.albion.data.ai.AiStatus
import com.marat.albion.data.ai.GroqModel
import kotlinx.coroutines.delay
import com.marat.albion.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Предупреждение для раздела бесед: Groq из России доступен только через VPN. */
@Composable
fun VpnNotice(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(AlbionIcons.Info, null, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Включите VPN. Собеседник работает через Groq, а из России он доступен только через VPN. " +
            "Голосовой ввод работает и без интернета.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Какая модель отвечает сейчас. Выбирать вручную нечего: при исчерпанном лимите клиент сам спускается
 * к следующей модели, а когда лимит восстанавливается — возвращается к самой сильной.
 */
@Composable
private fun ModelLadderInfo(status: AiStatus) {
    // Паузы истекают сами по себе: раз в полминуты пересчитываем, кто сейчас отвечает.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val current = status.model(now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(current?.let { "Сейчас отвечает: ${it.title}" } ?: "Лимиты всех моделей исчерпаны",
            style = MaterialTheme.typography.titleSmall)
        Text("Модель выбирается сама: начинает самая сильная, при исчерпании лимита — следующая, " +
            "а когда лимит восстановится, снова самая сильная.", style = MaterialTheme.typography.bodySmall, color = muted)
        GroqModel.entries.forEach { model ->
            val until = status.restUntil[model.id]?.takeIf { it > now }
            val state = when {
                model == current -> "отвечает"
                until != null -> "лимит вернётся через ${restLeft(until - now)}"
                else -> "в запасе"
            }
            Text("${model.title} · $state", style = MaterialTheme.typography.bodySmall,
                color = if (model == current) MaterialTheme.colorScheme.onSurface else muted)
        }
    }
}

private fun restLeft(ms: Long): String {
    val minutes = (ms / 60_000 + 1).coerceAtLeast(1)
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

/** Подключение собеседника: ключ Groq, лестница моделей и проверка связи. */
@Composable
fun AiControls() {
    val c = LocalAppContainer.current
    val status by c.ai.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /** [candidate] — новый ключ: сохраняется, только если Groq его принял. */
    fun verify(candidate: String? = null) {
        checking = true
        result = null
        error = null
        scope.launch {
            try {
                c.groq.verify(candidate)
                candidate?.let { c.ai.setKey(it); key = "" }
                result = "Связь с Groq есть, ключ подходит."
            }
            catch (e: CancellationException) { throw e }
            catch (e: AiException) { error = e.message }
            catch (_: Exception) { error = "Не удалось проверить ключ. Проверьте интернет и VPN." }
            finally { checking = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Собеседника, разбор ошибок, переводы реплик и подсказки к трудным словам выполняет ИИ в облаке Groq. " +
            "Бесплатный ключ выдают после регистрации по почте.", style = MaterialTheme.typography.bodyMedium)
        VpnNotice()
        if (!status.hasKey) {
            OutlinedTextField(key, { key = it.trim(); error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ключ Groq (gsk_…)") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            FieldError(error)
            PrimaryButton(if (checking) "Проверяю ключ…" else "Сохранить ключ", { verify(key) }, Modifier.fillMaxWidth(),
                enabled = key.length >= 20 && !checking)
            GhostButton("Получить ключ на console.groq.com", { uri.openUri("https://console.groq.com/keys") })
        } else {
            ModelLadderInfo(status)
            if (status.builtIn) Text("Ключ Groq встроен в сборку, вводить его не нужно.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FieldError(error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(if (checking) "Проверяю…" else "Проверить связь", { verify() }, enabled = !checking)
                if (!status.builtIn) TextButton(onClick = { c.ai.clearKey(); result = null; error = null }) { Text("Сменить ключ") }
            }
        }
    }
}
