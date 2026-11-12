package com.marat.albion.ui.games

import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.theme.*

internal val Game.editionTitle: String get() = when (this) {
    Game.TYPE_ANSWER -> "Слова"
    Game.IRREGULAR -> "Формы глагола"
    Game.GRAMMAR_CHOICE -> "Грамматика"
    Game.GRAMMAR_GAP -> "Письмо"
    Game.SENTENCE -> "Порядок слов"
    Game.TENSES -> "Времена"
    Game.PRONUNCIATION -> "Речь"
    Game.SHADOWING -> "Речь и ритм"
    Game.DICTATION -> "Слух"
    Game.CLOZE -> "Контекст"
    Game.FIX_ERROR -> "Ошибки"
    Game.MINIMAL_PAIRS -> "Звуки"
}

/** Код формата совпадает с нумерацией каталога практики: «02.1» — первое упражнение раздела 02. */
internal val Game.editionCode: String get() {
    val group = practiceGroups.indexOfFirst { this in it.second }
    return "%02d.%d".format(group + 1, practiceGroups[group].second.indexOf(this) + 1)
}

internal fun practiceTransition(reduced: Boolean): ContentTransform =
    fadeIn(tween(if (reduced) 0 else 280, easing = Easing.Standard)) togetherWith
        fadeOut(tween(if (reduced) 0 else 180, easing = Easing.Standard))

/** One plain working surface for all eight exercises, including compact keyboard mode. */
@Composable
internal fun PracticeStage(
    label: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerLow)
        .border(.75.dp, scheme.outlineVariant, shape)
        .padding(if (compact) 14.dp else 20.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        if (!compact) {
            SignalLabel(label, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
        content()
    }
}

@Composable
@Suppress("UNUSED_PARAMETER") // Номер больше не печатается: подпись раздела — обычный заголовок.
internal fun PracticeSectionLabel(number: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()
        .padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** Fixed actions have a clear boundary from the scrollable work area. */
@Composable
internal fun PracticeActionShelf(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth().drawBehind {
        drawLine(line, Offset.Zero, Offset(size.width, 0f), .75.dp.toPx())
    }.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Each skill keeps its recognisable icon in the setup header's original 64 dp slot. */
@Composable
internal fun PracticeEmblem(game: Game) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Icon(game.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun PracticeAudioButton(playing: Boolean, onClick: () -> Unit, size: Dp = 48.dp, primary: Boolean = true) {
    com.marat.albion.ui.components.AudioButton(playing, onClick, size = size.coerceAtLeast(48.dp), primary = primary)
}

