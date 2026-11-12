package com.marat.albion.ui.listening

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Panel

/** Вход в «Аудио с текстом» из «Чтения» — отдельной вкладки нет. */
@Composable
fun ListeningEntry(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Panel(modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ListeningIcons.Headphones, null, Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Аудио с текстом", style = MaterialTheme.typography.titleMedium)
                Text("Рассказы и песни: слушайте и следите по тексту", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AlbionIcons.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}
