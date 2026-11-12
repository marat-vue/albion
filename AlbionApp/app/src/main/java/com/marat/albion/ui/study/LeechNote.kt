package com.marat.albion.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.repo.Leech
import com.marat.albion.data.repo.LeechHint
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.QuietLinearProgressIndicator
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.theme.AlbionFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Пометка над трудным словом: сколько раз оно забывалось. */
@Composable
internal fun LeechLabel(lapses: Int, modifier: Modifier = Modifier) {
    SignalLabel("Трудное слово · забывалось $lapses ${com.marat.albion.ui.library.pluralRu(lapses, "раз", "раза", "раз")}",
        modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

/** Совет, подсказка-мнемоника и «Отложить на неделю» под оценками трудного слова. */
@Composable
internal fun LeechActions(card: CardEntity, lapses: Int, onPostpone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            if (lapses >= Leech.REWRITE_LAPSES) "Слово никак не держится. Возможно, его стоит убрать из коллекции или выучить в другой форме — с примером из книги."
            else "Слово не держится. Прочитайте примеры вслух и придумайте связь с русским словом — так легче запомнить.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LeechHintBlock(card)
        Spacer(Modifier.height(8.dp))
        GhostButton("Отложить на неделю", onClick = onPostpone, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Мнемоника и контрастные примеры: модель на устройстве пишет черновик только по нажатию,
 * результат сохраняется и правится вручную. Показывается после ответа — до него подсказка выдала бы слово.
 */
@Composable
private fun LeechHintBlock(card: CardEntity) {
    val c = LocalAppContainer.current
    val hint by remember(card.id) { c.leechHints.observe(card.id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var busy by remember(card.id) { mutableStateOf(false) }
    var error by remember(card.id) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(card.id) { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось придумать подсказку. Попробуйте ещё раз." }
            finally { busy = false }
        }
    }

    val current = hint
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current != null && !busy) HintPanel(current)
        if (busy) {
            Text("Модель придумывает подсказку — это займёт до минуты…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            QuietLinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val compact = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            when {
                current == null -> GhostButton("Придумать мнемонику", onClick = { run { c.leechHints.hint(card) } },
                    modifier = Modifier.weight(1f), icon = AlbionIcons.Lightbulb, contentPadding = compact, enabled = !busy)
                !current.edited -> GhostButton("Другой вариант", onClick = { run { c.leechHints.regenerate(card) } },
                    modifier = Modifier.weight(1f), contentPadding = compact, enabled = !busy)
            }
            GhostButton(if (current == null) "Написать свою" else "Изменить", onClick = { editing = true },
                modifier = Modifier.weight(1f), contentPadding = compact, enabled = !busy)
        }
    }
    if (editing) HintEditor(card, current, onSave = { mnemonic, examples ->
        editing = false
        scope.launch { c.leechHints.edit(card.id, mnemonic, examples) }
    }, onDismiss = { editing = false })
}

@Composable
private fun HintPanel(hint: LeechHint) {
    Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Eyebrow(if (hint.edited) "Ваша подсказка" else "Подсказка модели · черновик")
        Spacer(Modifier.height(6.dp))
        if (hint.mnemonic.isNotBlank()) {
            Text(hint.mnemonic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        hint.examples.forEach { example ->
            Spacer(Modifier.height(6.dp))
            Text(example, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AlbionFonts.Reading, fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface)
        }
        if (!hint.edited) {
            Spacer(Modifier.height(8.dp))
            Text("Лучше всего работает связь, которую вы придумали сами, — поправьте черновик под себя.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HintEditor(card: CardEntity, hint: LeechHint?, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var mnemonic by rememberSaveable(card.id) { mutableStateOf(hint?.mnemonic.orEmpty()) }
    var examples by rememberSaveable(card.id) { mutableStateOf(hint?.examples.orEmpty().joinToString("\n")) }
    ScrollableSheet("Подсказка · ${card.term}", onDismiss) {
        OutlinedTextField(mnemonic, { mnemonic = it.take(400) }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("Ассоциация") }, placeholder = { Text("Созвучное русское слово и яркий образ") })
        OutlinedTextField(examples, { examples = it.take(800) }, Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Примеры — по одному на строку") })
        Text("Оставьте оба поля пустыми, чтобы удалить подсказку.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton("Сохранить", onClick = { onSave(mnemonic, examples) })
    }
}
