package com.marat.albion.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.components.SettingsShortcut
import com.marat.albion.ui.components.SignalLabel
import com.marat.albion.ui.components.drawRingProgress
import com.marat.albion.ui.theme.AnimatedNumber
import com.marat.albion.ui.theme.Springs
import com.marat.albion.ui.theme.quietFloatAsState
import java.util.Calendar
import java.util.Locale

/** Приветствие по времени суток — редакционный заголовок главной вместо логотипа. */
internal fun homeGreeting(hour: Int): String = when (hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

/** The introduction stays in the daily overview at every window and font size. */
@Composable
internal fun HomeHero(state: HomeState, date: String, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SignalLabel(date.replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }, Modifier.weight(1f))
            SettingsShortcut(onSettings)
        }
        Text(homeGreeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.displaySmall)
        DailyProgress(state)
    }
}

@Composable
private fun DailyProgress(state: HomeState) {
    val progress = if (state.dailyGoal > 0) state.answersToday.toFloat() / state.dailyGoal else 0f
    val status = if (state.answersToday >= state.dailyGoal) "Выполнено" else "В процессе"
    val narrow = LocalDensity.current.fontScale > 1.6f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!narrow) GoalRing(progress)
        Column(Modifier.weight(1f)) { GoalCount(state) }
        SignalLabel(status)
    }
}

/** Кольцо цели дня: дуга догоняет значение на пружине. */
@Composable
private fun GoalRing(progress: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary
    val p by quietFloatAsState(progress.coerceIn(0f, 1f), Springs.smooth(), "goalRing")
    Canvas(Modifier.size(48.dp).clearAndSetSemantics {}) {
        drawRingProgress(track, arc, p, 5.dp.toPx())
    }
}

@Composable
private fun GoalCount(state: HomeState) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(state.answersToday, MaterialTheme.typography.headlineSmall)
            Text(" / ${state.dailyGoal}", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("цель дня", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
