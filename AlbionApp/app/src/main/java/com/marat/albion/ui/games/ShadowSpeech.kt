package com.marat.albion.ui.games

import com.marat.albion.ui.components.QuietLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.marat.albion.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marat.albion.data.speech.SpeechCheck
import com.marat.albion.data.speech.WordCheck
import com.marat.albion.data.speech.WordVerdict
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.LocalNarrow
import com.marat.albion.ui.components.SignalLabel

/** Проверка разборчивости попытки через Whisper. */
internal sealed interface ClarityState {
    data object Idle : ClarityState
    data object Checking : ClarityState
    data class Done(val check: SpeechCheck) : ClarityState
    data class Failed(val message: String) : ClarityState
}

/**
 * Что услышала машина: каждое слово фразы — узнано, нечётко, услышано иначе или пропущено.
 * Дополняет разбор мелодики: тот сравнивает интонацию, а не звуки.
 */
@Composable
internal fun SpeechCheckCard(state: ClarityState, modelReady: Boolean, onInstall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .background(scheme.surfaceContainerLow, shape).border(.75.dp, scheme.outlineVariant, shape)
            .padding(if (LocalNarrow.current) 16.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SignalLabel("Разборчивость")
        when {
            !modelReady -> {
                Text("Установите Whisper — он покажет, какие слова вашей записи узнаются, а какие звучат нечётко. Работает без интернета.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                TextButton(onInstall) { Text("Установить распознавание речи") }
            }
            state is ClarityState.Checking -> {
                Text("Распознаю, что слышно в записи…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = scheme.primary, trackColor = scheme.outlineVariant)
            }
            state is ClarityState.Failed -> Text(state.message, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            state is ClarityState.Done -> CheckResult(state.check)
            else -> Text("Запишите попытку — проверю, узнаётся ли каждое слово.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckResult(check: SpeechCheck) {
    val scheme = MaterialTheme.colorScheme
    if (check.heard.isEmpty()) {
        Text("Whisper не разобрал ни одного слова. Говорите громче и чётче, ближе к телефону.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.error)
        return
    }
    Text(
        when {
            check.allClear -> "Все слова узнаются уверенно"
            check.recognized == check.words.size -> "Слова узнаются, но не все чётко"
            else -> "Узнаётся ${check.recognized} из ${check.words.size} ${plural(check.words.size)}"
        },
        style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        check.words.forEach { WordChip(it) }
    }
    val problems = check.words.filter { it.verdict != WordVerdict.CLEAR }
    if (problems.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        problems.forEach { word ->
            Text(
                "«${word.expected}» — " + when (word.verdict) {
                    WordVerdict.UNCLEAR -> "узнаётся с трудом: звуки размыты. Послушайте диктора медленно и повторите это слово отдельно."
                    WordVerdict.MISHEARD -> "услышано как «${word.heard?.text}». Сравните звуки с диктором."
                    else -> "не слышно. Возможно, слово проглочено — произнесите его отчётливее."
                },
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
            )
        }
    }
    Text("Услышано: «${check.heard.joinToString(" ") { it.text }}»",
        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    Text("Whisper проверяет, понятна ли речь, и лёгкий акцент прощает. «Нечётко» — слово узнано, но с сомнением.",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
}

@Composable
private fun WordChip(word: WordCheck) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val (background, text, caption) = when (word.verdict) {
        WordVerdict.CLEAR -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, "узнано")
        WordVerdict.UNCLEAR -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, "нечётко")
        WordVerdict.MISHEARD -> Triple(scheme.errorContainer, scheme.onErrorContainer, "как «${word.heard?.text}»")
        WordVerdict.MISSING -> Triple(scheme.errorContainer, scheme.onErrorContainer, "не слышно")
    }
    Column(
        Modifier.background(background, shape)
            .then(if (word.verdict == WordVerdict.UNCLEAR) Modifier.border(1.dp, scheme.secondary, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "${word.expected}: $caption" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (word.verdict == WordVerdict.CLEAR) {
                Icon(AlbionIcons.Check, null, Modifier.size(14.dp), tint = scheme.tertiary)
                Spacer(Modifier.width(4.dp))
            }
            Text(word.expected, style = MaterialTheme.typography.titleSmall, color = text)
        }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = text.copy(alpha = 0.75f))
    }
}

private fun plural(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "слова"
    else -> "слов"
}
