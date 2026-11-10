package com.marat.albion.ui.nav

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.marat.albion.data.content.Deck
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigatorTest {
    private fun navigator() = Navigator(
        primaryRoutes.associateWith { NavBackStack<NavKey>(it) },
        mutableIntStateOf(0),
    )

    @Test fun switchingTabsRetainsEachStack() {
        val nav = navigator()
        nav.open(DictionaryRoute(Deck.WORDS))
        nav.selectTab(GrammarRoute)
        nav.open(TopicRoute("articles"))
        nav.selectTab(HomeRoute)
        assertEquals(listOf(HomeRoute, DictionaryRoute(Deck.WORDS)), nav.backStack.toList())
        nav.selectTab(GrammarRoute)
        assertEquals(listOf(GrammarRoute, TopicRoute("articles")), nav.backStack.toList())
    }

    @Test fun openingFromListReplacesShownDetailButLinksStack() {
        val nav = navigator()
        nav.selectTab(GrammarRoute)
        nav.openDetail(TopicRoute("articles"))
        // Ссылка внутри темы — обычный переход: «Назад» вернёт к прежней теме.
        nav.open(TopicRoute("plurals"))
        assertEquals(listOf(GrammarRoute, TopicRoute("articles"), TopicRoute("plurals")), nav.backStack.toList())
        // Выбор в оглавлении заменяет открытые темы: на планшете они стоят рядом со списком.
        nav.openDetail(TopicRoute("tenses"))
        assertEquals(listOf(GrammarRoute, TopicRoute("tenses")), nav.backStack.toList())
        nav.back()
        assertEquals(listOf(GrammarRoute), nav.backStack.toList())
    }

    @Test fun selectingCurrentTabReturnsToItsRootOnly() {
        val nav = navigator()
        nav.open(DictionaryRoute(Deck.IDIOMS))
        nav.selectTab(GrammarRoute)
        nav.open(TopicRoute("articles"))
        nav.selectTab(GrammarRoute)
        assertEquals(listOf(GrammarRoute), nav.backStack.toList())
        nav.selectTab(HomeRoute)
        assertEquals(DictionaryRoute(Deck.IDIOMS), nav.backStack.last())
    }

    @Test fun backPopsScreenThenReturnsHomeFromTabRoot() {
        val nav = navigator()
        nav.selectTab(LibraryRoute)
        nav.open(ReaderRoute("book"))
        nav.back()
        assertEquals(listOf(LibraryRoute), nav.backStack.toList())
        nav.back()
        assertEquals(HomeRoute, nav.selectedTab)
        nav.back()
        assertEquals(listOf(HomeRoute), nav.backStack.toList())
    }

    @Test fun settingsReturnsToItsCaller() {
        val nav = navigator()
        nav.selectTab(StatsRoute)
        nav.openSettings()
        nav.openSettings()
        assertEquals(listOf(StatsRoute, SettingsRoute), nav.backStack.toList())
        nav.back()
        assertEquals(StatsRoute, nav.selectedTab)
        assertEquals(listOf(StatsRoute), nav.backStack.toList())
    }

    @Test fun openingExistingRoutePopsToItWithoutDuplicateKeys() {
        val nav = navigator()
        nav.open(DictionaryRoute(Deck.WORDS))
        nav.open(BrowseRoute(Deck.WORDS))
        nav.open(CardRoute("card"))
        nav.open(DictionaryRoute(Deck.WORDS))
        assertEquals(listOf(HomeRoute, DictionaryRoute(Deck.WORDS)), nav.backStack.toList())
    }

    @Test fun replaceKeepsRootAndReusesEarlierRoute() {
        val nav = navigator()
        nav.open(DictionaryRoute(Deck.WORDS))
        nav.open(BrowseRoute(Deck.WORDS))
        nav.replace(CardRoute("card"))
        assertEquals(listOf(HomeRoute, DictionaryRoute(Deck.WORDS), CardRoute("card")), nav.backStack.toList())
        nav.replace(DictionaryRoute(Deck.WORDS))
        assertEquals(listOf(HomeRoute, DictionaryRoute(Deck.WORDS)), nav.backStack.toList())
    }

    @Test fun crossSectionLinksReturnToTheOriginatingSection() {
        val nav = navigator()
        nav.selectTab(GamesRoute)
        nav.open(TopicRoute("articles"))
        nav.back()
        assertEquals(GamesRoute, nav.selectedTab)
        assertEquals(listOf(GamesRoute), nav.backStack.toList())
    }

    @Test fun contentsLinkOpensSectionRootWhileSwitchingTabPreservesItsPage() {
        val nav = navigator()
        nav.selectTab(GrammarRoute)
        nav.open(TopicRoute("articles"))
        nav.open(GrammarRoute)
        assertEquals(listOf(GrammarRoute), nav.backStack.toList())
    }

    @Test fun openingTheRuleKeepsTheQuizInTheStackEvenAboveTheSameLesson() {
        val nav = navigator()
        nav.selectTab(GrammarRoute)
        val lesson = TopicRoute("articles")
        val quiz = TopicQuizRoute(1, "articles", "Articles")
        nav.open(lesson)
        nav.open(quiz)
        nav.open(TopicRoute("articles", fromQuiz = true))
        assertEquals(listOf(GrammarRoute, lesson, quiz, TopicRoute("articles", fromQuiz = true)), nav.backStack.toList())
        nav.back()
        assertEquals(listOf(GrammarRoute, lesson, quiz), nav.backStack.toList())
    }
}
