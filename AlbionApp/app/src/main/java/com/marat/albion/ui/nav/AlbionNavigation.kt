package com.marat.albion.ui.nav

import androidx.compose.material3.MaterialTheme
import com.marat.albion.ui.components.AlbionIcons
import androidx.activity.compose.BackHandler
import com.marat.albion.ui.theme.QuietAnimatedVisibility as AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.marat.albion.ui.theme.Springs
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.marat.albion.ui.browse.BrowseScreen
import com.marat.albion.ui.browse.CardDetailScreen
import com.marat.albion.ui.components.CenteredColumn
import com.marat.albion.ui.components.DashboardWidth
import com.marat.albion.ui.components.FocusWidth

import com.marat.albion.ui.components.ReadableWidth
import com.marat.albion.ui.deck.DictionaryScreen
import com.marat.albion.ui.games.GameHost
import com.marat.albion.ui.games.TopicQuizHost
import com.marat.albion.ui.games.GamesScreen
import com.marat.albion.ui.grammar.GrammarScreen
import com.marat.albion.ui.grammar.TopicScreen
import com.marat.albion.ui.home.HomeScreen
import com.marat.albion.ui.library.LibraryScreen
import com.marat.albion.ui.library.ReaderScreen
import com.marat.albion.ui.settings.SettingsScreen
import com.marat.albion.ui.stats.StatsScreen
import com.marat.albion.ui.study.StudyScreen
import com.marat.albion.ui.theme.Easing
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.LocalFeedback

internal data class Tab(val route: TopLevel, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

internal val tabs = listOf(
    Tab(HomeRoute, "Сегодня", AlbionIcons.Layers, AlbionIcons.LayersFilled),
    Tab(GrammarRoute, "Учебник", AlbionIcons.MenuBook, AlbionIcons.MenuBookFilled),
    Tab(LibraryRoute, "Чтение", AlbionIcons.AutoStories, AlbionIcons.AutoStoriesFilled),
    Tab(GamesRoute, "Практика", AlbionIcons.EditNote, AlbionIcons.EditNoteFilled),
    Tab(StatsRoute, "Прогресс", AlbionIcons.Insights, AlbionIcons.InsightsFilled),
)

private val none = ContentTransform(androidx.compose.animation.EnterTransition.None, androidx.compose.animation.ExitTransition.None, sizeTransform = null)

// Общая ось X: новый экран въезжает справа на пружине, прежний чуть уходит в глубину.
private fun forward(reduced: Boolean): ContentTransform = if (reduced) none else
    (slideInHorizontally(Springs.smooth()) { it / 5 } + fadeIn(tween(220, 40))) togetherWith
        (slideOutHorizontally(Springs.smooth()) { -it / 12 } + fadeOut(tween(160)) + scaleOut(Springs.smooth(), targetScale = 0.97f))

private fun backward(reduced: Boolean): ContentTransform = if (reduced) none else
    (slideInHorizontally(Springs.smooth()) { -it / 12 } + fadeIn(tween(220)) + scaleIn(Springs.smooth(), initialScale = 0.97f)) togetherWith
        (slideOutHorizontally(Springs.smooth()) { it / 5 } + fadeOut(tween(160)))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AlbionNavigation() {
    val reduced = LocalReducedMotion.current
    val feedback = LocalFeedback.current
    val stacks = primaryRoutes.associateWith { route -> key(route) { rememberNavBackStack(route) } }
    val selectedIndex = rememberSaveable { mutableIntStateOf(0) }
    val navigator = remember(stacks, selectedIndex) { Navigator(stacks, selectedIndex) }
    val backStack = navigator.backStack
    val current = backStack.lastOrNull()
    val showBar = current !is StudyRoute && current !is ReaderRoute && current !is GameRoute &&
        current !is TopicQuizRoute && current != SettingsRoute && current !is ChatRoute &&
        current !is ListeningPlayerRoute && current !is ListeningPracticeRoute
    BackHandler(enabled = backStack.size == 1 && navigator.selectedTab != HomeRoute) { navigator.back() }

    val provider = entryProvider<NavKey> {
        entry<HomeRoute> { HomeScreen(navigator) }
        entry<GrammarRoute> { GrammarScreen(navigator) }
        entry<GamesRoute> { GamesScreen(navigator) }
        entry<GameRoute> { GameHost(it.game, navigator, it.deck, it.onlyMistakes, it.count) }
        entry<LibraryRoute> { LibraryScreen(navigator) }
        entry<ReaderRoute> { ReaderScreen(it.bookId, navigator) }
        entry<StatsRoute> { StatsScreen(navigator) }
        entry<SettingsRoute> { SettingsScreen(navigator::back) }
        entry<DictionaryRoute> { DictionaryScreen(it.deck, navigator) }
        entry<StudyRoute> { StudyScreen(it.source, navigator, it.answerLimit) }
        entry<BrowseRoute> { BrowseScreen(it.deck, navigator, it.collectionId) }
        entry<CollectionRoute> { com.marat.albion.ui.collections.CollectionScreen(it.id, navigator) }
        entry<CardRoute> { CardDetailScreen(it.cardId, navigator) }
        entry<TopicRoute> { TopicScreen(it.topicId, navigator, fromQuiz = it.fromQuiz) }
        entry<TopicQuizRoute> { TopicQuizHost(it.topic, it.topicId, it.title, navigator) }
        entry<ConversationsRoute> { com.marat.albion.ui.conversation.ConversationsScreen(navigator) }
        entry<ChatRoute> { com.marat.albion.ui.conversation.ChatScreen(it.conversationId, navigator) }
        entry<ListeningRoute> { com.marat.albion.ui.listening.ListeningScreen(navigator) }
        entry<ListeningPlayerRoute> { com.marat.albion.ui.listening.ListeningPlayerScreen(it.trackId, navigator) }
        entry<ListeningPracticeRoute> {
            com.marat.albion.ui.listening.ListeningPracticeScreen(it.trackId, it.mode, it.fromSegment, navigator::back)
        }
    }
    // Декораторы всех стеков остаются в композиции, даже когда отображается другой раздел.
    val entries = stacks.mapValues { (tab, stack) ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = { route ->
                    val original = provider(route)
                    NavEntry(route, contentKey = "$tab:$route", metadata = original.metadata + paneMetadata(route)) {
                        CenteredColumn(maxWidthFor(route)) { original.Content() }
                    }
                },
            )
        }
    }

    val chromeVisible = showBar && !WindowInsets.isImeVisible
    val listIndex = backStack.indexOfLast { paneOf(it) == Pane.List }
    val listDetailEligible = listIndex >= 0 && backStack.drop(listIndex + 1).all { paneOf(it) == Pane.Detail }
    NavigationWindow(current, chromeVisible, listDetailEligible) { listDetail, navigationHeight ->
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // На планшете вкладки — боковой панелью слева, на телефоне — снизу.
            val railMetrics = navigationRailMetrics(maxWidth, navigationHeight)
            val wide = railMetrics.enabled
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    AnimatedVisibility(
                        visible = chromeVisible && !wide,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220, easing = Easing.Standard)) { if (reduced) 0 else it / 3 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { if (reduced) 0 else it / 3 },
                    ) {
                        BottomBar(navigator.selectedTab) { feedback.selection(); navigator.selectTab(it) }
                    }
                },
            ) { padding ->
                val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                val rail = chromeVisible && wide
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = rail,
                        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220, easing = Easing.Standard)) { if (reduced) 0 else -it / 3 },
                        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { if (reduced) 0 else -it / 3 },
                    ) {
                        Box(Modifier.height(navigationHeight)) {
                            SideRail(navigator.selectedTab, railMetrics) { feedback.selection(); navigator.selectTab(it) }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val bounds = LocalNavigationBounds.current
                        val railWidth = with(LocalDensity.current) { if (rail) railMetrics.width.roundToPx() else 0 }
                        val spatialMotion = reduced || LocalWindowFolds.current.orEmpty().isNotEmpty()
                        val sceneBounds = if (LocalLayoutDirection.current == LayoutDirection.Rtl) bounds.copy(right = bounds.right - railWidth)
                            else bounds.copy(left = bounds.left + railWidth)
                        CompositionLocalProvider(LocalSceneBounds provides sceneBounds) {
                            NavDisplay(
                                entries = entries.getValue(navigator.selectedTab),
                                onBack = { navigator.back() },
                                sceneStrategies = remember(listDetail, navigator) { listOf(ListDetailSceneStrategy(listDetail) { com.marat.albion.ui.grammar.GrammarOverview(navigator) }) },
                                modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)
                                    // Без нижней панели вкладок разделы сами отступают от системной навигации.
                                    .then(if (rail) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier),
                                transitionSpec = { forward(spatialMotion) },
                                popTransitionSpec = { backward(spatialMotion) },
                                predictivePopTransitionSpec = { backward(spatialMotion) },
                            )
                        }
                        // Прокручиваемый контент не должен просвечивать под статус-баром.
                        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
    }
}

/** Ширина колонки экрана: книга — на всю ширину, задания — уже, списки и текст — колонкой для чтения. */
private fun maxWidthFor(route: NavKey): Dp = when (route) {
    is ReaderRoute -> Dp.Infinity
    // Главная на планшете делится на две колонки: сегодня и слова.
    HomeRoute -> DashboardWidth
    is StudyRoute, is GameRoute, is TopicQuizRoute, is ChatRoute, is ListeningPracticeRoute -> FocusWidth
    else -> ReadableWidth
}
