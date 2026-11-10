package com.marat.albion.ui.nav

import com.marat.albion.ui.theme.QuietAnimatedContent as AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import com.marat.albion.ui.components.CenteredColumn
import com.marat.albion.ui.components.ReadableWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/** Роль экрана в двухпанельной вёрстке планшета. */
internal enum class Pane { List, Detail }

private const val PANE_KEY = "albion.pane"
private const val OVERVIEW_KEY = "albion.grammarOverview"
private const val PLACEHOLDER_KEY = "albion.placeholder"

/** Какие экраны — список, а какие — его подробности. Остальные всегда занимают весь экран. */
internal fun paneOf(route: NavKey): Pane? = when (route) {
    GrammarRoute -> Pane.List
    // Подбор слов в коллекцию ничего не открывает рядом — список на весь экран.
    is BrowseRoute -> if (route.collectionId == null) Pane.List else null
    is TopicRoute, is CardRoute -> Pane.Detail
    else -> null
}

/** Метаданные записи навигации: роль панели и подсказка для пустой правой панели. */
internal fun paneMetadata(route: NavKey): Map<String, Any> = when (paneOf(route)) {
    Pane.List -> mapOf(
        PANE_KEY to Pane.List,
        OVERVIEW_KEY to (route == GrammarRoute),
        PLACEHOLDER_KEY to if (route is BrowseRoute) "Выберите карточку в списке" else "Выберите урок на маршруте",
    )
    Pane.Detail -> mapOf(PANE_KEY to Pane.Detail)
    null -> emptyMap()
}

private val NavEntry<*>.pane get() = metadata[PANE_KEY] as? Pane

/**
 * На широком экране список и открытая из него страница показываются рядом:
 * оглавление учебника слева, тема справа. Стек навигации тот же, что и на телефоне, —
 * меняется только то, как он нарисован, поэтому «Назад» и сохранение состояния работают как обычно.
 */
internal class ListDetailSceneStrategy(private val enabled: Boolean, private val grammarOverview: @Composable () -> Unit) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        if (!enabled) return null
        val last = entries.lastOrNull() ?: return null
        return when (last.pane) {
            Pane.List -> ListDetailScene(list = last, detail = null, previousEntries = entries.dropLast(1), grammarOverview = grammarOverview)
            Pane.Detail -> {
                // Справа — последняя страница; между ней и списком могут лежать только другие страницы.
                val listIndex = entries.indexOfLast { it.pane == Pane.List }
                if (listIndex < 0 || entries.subList(listIndex + 1, entries.size).any { it.pane != Pane.Detail }) return null
                ListDetailScene(list = entries[listIndex], detail = last, previousEntries = entries.dropLast(1), grammarOverview = grammarOverview)
            }
            null -> null
        }
    }
}

private data class ListDetailScene(
    val list: NavEntry<NavKey>,
    val detail: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
    val grammarOverview: @Composable () -> Unit,
) : Scene<NavKey> {
    // Ключ — список: смена темы справа не перерисовывает весь экран переходом.
    override val key: Any = "list-detail:${list.contentKey}"
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(list, detail)
    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
            val density = LocalDensity.current
            val windowArea = LocalSceneBounds.current
            val fold = LocalWindowFolds.current.orEmpty().singleOrNull()
            val split = fold?.let { usablePanePartition(windowArea, it, density.density) }
            val width = with(density) { maxWidth.roundToPx() }
            val height = with(density) { maxHeight.roundToPx() }
            fun local(r: IntRect) = IntRect(r.left - windowArea.left, r.top - windowArea.top,
                r.right - windowArea.left, r.bottom - windowArea.top)
            val listWidth = with(density) { (maxWidth * .33f).coerceIn(320.dp, 380.dp).roundToPx() }
            val first = split?.first?.let(::local) ?: IntRect(0, 0, listWidth, height)
            val second = split?.second?.let(::local) ?: IntRect(listWidth + with(density) { 1.dp.roundToPx() }, 0, width, height)
            fun Modifier.at(r: IntRect): Modifier = absoluteOffset { IntOffset(r.left, r.top) }
                .requiredSize(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }).clipToBounds()
            if (split == null) Box(Modifier.absoluteOffset { IntOffset(first.right, 0) }.width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.at(first)) {
                CompositionLocalProvider(LocalNavigationBounds provides (split?.first ?: windowArea), LocalBesideDetail provides true, LocalPaneHeight provides with(density) { first.height.toDp() }) { list.Content() }
            }
            AnimatedContent(
                targetState = detail,
                contentKey = { it?.contentKey },
                transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.at(second),
                label = "detail",
            ) { entry ->
                CompositionLocalProvider(LocalNavigationBounds provides (split?.second ?: windowArea)) {
                    if (entry != null) entry.Content()
                    else if (list.metadata[OVERVIEW_KEY] == true) CenteredColumn(ReadableWidth) { grammarOverview() }
                    else DetailPlaceholder(list.metadata[PLACEHOLDER_KEY] as? String ?: "")
                }
            }
        }
    }
}

@Composable
private fun DetailPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** true — список нарисован рядом с открытой страницей: ему стоит держать её строку на виду. */
val LocalBesideDetail = staticCompositionLocalOf { false }
internal val LocalPaneHeight = staticCompositionLocalOf<Dp?> { null }

/** С какой ширины окна список и страница помещаются рядом без тесноты. */
internal val ListDetailMinWidth = 1000.dp
