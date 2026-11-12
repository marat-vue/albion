package com.marat.albion.ui.grammar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.grammar.GrammarNextStep
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.TopicQuizRoute
import com.marat.albion.ui.nav.TopicRoute

/** A recommendation is a starting point; a lesson opens only after the user's explicit choice. */
@Composable
internal fun GrammarOverview(navigator: Navigator) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "grammar-overview") { GrammarViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = grammarReviewTime()
    val next = GrammarNextStep.recommend(state.book, state.progress, state.quizTopics, now, lastId = state.lastTopic)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 24.dp, bottom = 24.dp + com.marat.albion.ui.components.bottomChrome),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ModuleHeading("02", "Обзор учебника", compact = true)
        if (state.loading) LoadingState("Подбираем следующий урок")
        else {
            val mastered = state.book.lessons.count { state.progress[it.id]?.masteredAt != null }
            val due = state.reviews.count { it.dueAt <= now }
            Text("Освоено $mastered из ${state.book.lessons.size} · к повторению $due",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (next != null) {
                val read = { navigator.openDetail(TopicRoute(next.topic.id)) }
                GrammarNextCard(next, onClick = {
                    val number = next.topic.number
                    if (next.kind == GrammarNextStep.Kind.Read || number == null) read()
                    else navigator.open(TopicQuizRoute(number, next.topic.id, next.topic.title))
                }, onRead = read)
                Text(next.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            } else if (state.book.lessons.isEmpty()) {
                EmptyState("Учебник пока недоступен", "Проверьте учебные материалы в настройках.")
                GhostButton("Открыть настройки", navigator::openSettings)
            } else {
                Panel { Text("Все доступные уроки пройдены", style = MaterialTheme.typography.titleLarge) }
            }
            Text("Выберите любой урок в маршруте, чтобы открыть правило и примеры.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
