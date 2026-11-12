package com.marat.albion.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marat.albion.data.repo.Leech
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.SignalLabel

/** Пометка над трудным словом: сколько раз оно забывалось. */
@Composable
internal fun LeechLabel(lapses: Int, modifier: Modifier = Modifier) {
    SignalLabel("Трудное слово · забывалось $lapses ${com.marat.albion.ui.library.pluralRu(lapses, "раз", "раза", "раз")}",
        modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

/** Совет и «Отложить на неделю» под оценками трудного слова. */
@Composable
internal fun LeechActions(lapses: Int, onPostpone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            if (lapses >= Leech.REWRITE_LAPSES) "Слово никак не держится. Возможно, его стоит убрать из коллекции или выучить в другой форме — с примером из книги."
            else "Слово не держится. Прочитайте примеры вслух и придумайте связь с русским словом — так легче запомнить.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GhostButton("Отложить на неделю", onClick = onPostpone, modifier = Modifier.fillMaxWidth())
    }
}
