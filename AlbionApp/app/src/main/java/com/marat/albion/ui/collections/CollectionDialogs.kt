package com.marat.albion.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.repo.CollectionRepository
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.FieldError
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.PaneAlertDialog
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.components.TextButton
import com.marat.albion.ui.theme.LocalFeedback
import com.marat.albion.ui.theme.controlBorder
import com.marat.albion.ui.theme.selectedBorder
import com.marat.albion.ui.theme.selectedContainer
import com.marat.albion.ui.theme.selectedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Название новой или существующей коллекции. [onSave] бросает исключение с текстом ошибки, если сохранить нельзя. */
@Composable
fun CollectionNameDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    initial: String = "",
    onSave: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val tooLong = name.trim().length > CollectionRepository.MAX_NAME
    val valid = name.isNotBlank() && !tooLong
    val save = {
        if (valid && !saving) {
            saving = true; error = null
            scope.launch {
                try { onSave(name) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Не удалось сохранить коллекцию. Попробуйте ещё раз." }
                finally { saving = false }
            }
        }
    }
    PaneAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Название") },
                    placeholder = { Text("Например, «Путешествия»") },
                    singleLine = true, enabled = !saving, isError = tooLong,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                FieldError(if (tooLong) "Сократите название до ${CollectionRepository.MAX_NAME} символов." else error)
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = valid && !saving) {
                Text(if (saving) "Сохраняем…" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") }
        },
    )
}

/** Отметки коллекций, в которых есть слово; отсюда же можно завести новую коллекцию с этим словом. */
@Composable
fun AddToCollectionsSheet(card: CardEntity, onDismiss: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val collections by remember { c.collections.observeAll() }.collectAsStateWithLifecycle(null)
    val containing by remember(card.id) { c.collections.observeContaining(card) }.collectAsStateWithLifecycle(emptySet())
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScrollableSheet("В коллекцию · ${card.term}", onDismiss) {
        val list = collections
        if (list != null && list.isEmpty()) {
            Text("У вас пока нет коллекций. Соберите свою — например, слова к поездке или из любимого сериала.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list?.forEach { collection ->
            CheckRow(collection.name, collection.id in containing) { checked ->
                error = null
                scope.launch {
                    try { c.collections.setContains(collection.id, card, checked) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Не удалось изменить коллекцию. Попробуйте ещё раз." }
                }
            }
        }
        FieldError(error)
        GhostButton("Новая коллекция", { creating = true }, icon = AlbionIcons.Add)
    }
    if (creating) CollectionNameDialog("Новая коллекция", "Создать", { creating = false }) { name ->
        val id = c.collections.create(name)
        c.collections.add(id, listOf(card))
        creating = false
    }
}

/** Строка с флажком: та же плитка, что у вариантов выбора, но отметку можно снять. */
@Composable
internal fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, supporting: String? = null, onChange: (Boolean) -> Unit) {
    val feedback = LocalFeedback.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val fg = if (checked) scheme.selectedContent else scheme.onSurface
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (checked) scheme.selectedContainer else scheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, if (checked) scheme.selectedBorder else scheme.controlBorder, MaterialTheme.shapes.medium)
            .toggleable(checked, interaction, null, role = Role.Checkbox) { feedback.selection(); onChange(it) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                color = if (checked) scheme.selectedContent else scheme.onSurfaceVariant)
        }
        Box(Modifier.size(22.dp).border(1.5.dp, if (checked) fg else scheme.controlBorder, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
            if (checked) Icon(AlbionIcons.Check, null, Modifier.size(18.dp), tint = fg)
        }
    }
}
