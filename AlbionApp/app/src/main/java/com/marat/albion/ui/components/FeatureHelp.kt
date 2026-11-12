package com.marat.albion.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.marat.albion.ui.components.PaneAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import kotlinx.coroutines.launch

/** Инструкция один раз при знакомстве с разделом, затем — по кнопке справки. */
@Composable
fun FeatureHelp(key: String, title: String, text: String, showOnFirstUse: Boolean = false, filled: Boolean = true) {
    val c = LocalAppContainer.current
    val viewed by c.settings.viewedHelp.collectAsStateWithLifecycle(null)
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var presented by rememberSaveable(key) { mutableStateOf(false) }

    LaunchedEffect(viewed, key) {
        val known = viewed ?: return@LaunchedEffect
        if (showOnFirstUse && key !in known && !presented) {
            presented = true
            expanded = true
        }
    }

    fun dismiss() {
        expanded = false
        // Запись завершается, даже если пользователь сразу уходит с экрана.
        c.appScope.launch { c.settings.markHelpViewed(key) }
    }

    // В шапках — в рамке, как «Назад» и «Настройки»; в строке занятия, где все кнопки без рамки, — без неё.
    RoundIconButton(AlbionIcons.HelpOutline, "Справка: $title", onClick = { presented = true; expanded = true }, spin = -20f, filled = filled)
    if (expanded) {
        AlertDialog(
            onDismissRequest = ::dismiss,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = { PrimaryButton("Понятно", onClick = ::dismiss) },
        )
    }
}
