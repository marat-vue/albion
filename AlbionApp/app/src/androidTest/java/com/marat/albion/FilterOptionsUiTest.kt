package com.marat.albion

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.ui.browse.BrowseScreen
import com.marat.albion.ui.components.MeasureNarrow
import com.marat.albion.ui.games.GamesScreen
import com.marat.albion.ui.games.Game
import com.marat.albion.ui.games.GameHost
import com.marat.albion.ui.library.LibraryScreen
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.theme.AlbionTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilterOptionsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container

    @Test fun practiceFilterAppliesAndSurvivesRecreation(): Unit = runBlocking {
        c.importer.importIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            activate(await { it.text?.toString() == "Практика" })
            choose("Навык", "Речь и слух", "practice-menu")
            scrollTo { it.text?.toString() == "Произношение" }
            await { it.text?.toString() == "Шэдоуинг" }
            assertNull(find(root()) { it.text?.toString() == "Соберите предложение" })
            capture("practice-selected")
            scenario.recreate()
            val restoredSkill = scrollTo { it.contentDescription?.toString() == "Навык" && visibleText(it).contains("Речь и слух") }
            capture("practice-restored")
            assertTrue("Restored skill: ${visibleText(restoredSkill)}", visibleText(restoredSkill).contains("Речь и слух"))
            choose("Навык", "Все")
            scrollTo { it.text?.toString() == "Словарный запас" }

            activate(await { it.text?.toString() == "Чтение" })
            choose("Статус чтения", "Не начаты", "library-menu")
            choose("Порядок книг", "По названию")
            scenario.recreate()
            assertTrue(visibleText(scrollTo { it.contentDescription?.toString() == "Статус чтения" && visibleText(it).contains("Не начаты") }).contains("Не начаты"))
            assertTrue(visibleText(scrollTo { it.contentDescription?.toString() == "Порядок книг" && visibleText(it).contains("По названию") }).contains("По названию"))
            capture("library-selected")
        }
    }

    @Test fun completeMenusRemainUsableOnNarrowScreensWithLargeText(): Unit = runBlocking {
        c.importer.importIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((width, scale, dark) in listOf(Triple(411, 1f, false), Triple(320, 2f, true))) {
                for (screen in listOf("practice", "library", "cards", "grammar")) {
                    val name = "$screen-$width-$scale"
                    val nav = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
                    scenario.onActivity { activity -> activity.setContent {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalAppContainer provides c, LocalDensity provides Density(density, scale)) {
                            key(name) {
                                AlbionTheme(darkTheme = dark) {
                                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                        MeasureNarrow(Modifier.width(width.dp).height(740.dp)) {
                                            when (screen) {
                                                "practice" -> GamesScreen(nav)
                                                "library" -> LibraryScreen(nav)
                                                "grammar" -> GameHost(Game.GRAMMAR_CHOICE, nav)
                                                else -> BrowseScreen(Deck.WORDS, nav)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } }
                    instrumentation.waitForIdleSync(); SystemClock.sleep(500)
                    if (screen == "grammar") {
                        val intro = await { it.text?.toString() in setOf("Понятно", "Начать") }
                        if (intro.text?.toString() == "Понятно") activate(intro)
                    }
                    val label = when (screen) { "practice" -> "Навык"; "library" -> "Статус чтения"; "grammar" -> "Уровень заданий"; else -> "Статус карточек" }
                    val option = when (screen) { "practice" -> "Речь и слух"; "library" -> "Прочитаны"; "grammar" -> "C1"; else -> "Пора повторить" }
                    if (screen == "cards") find(root()) { it.text?.toString() == "Фильтры" }?.let(::activate)
                    choose(label, option, "$name-menu")
                    assertTrue("Selected value was lost ($name)", visibleText(scrollTo { it.contentDescription?.toString() == label }).contains(option))
                    if (screen == "cards") choose("Уровень", "C1", "$name-level-menu")
                    capture("$name-selected")
                }
            }
        }
    }

    private fun choose(label: String, option: String, screenshot: String? = null) {
        activate(scrollTo { it.contentDescription?.toString() == label })
        val item = scrollTo { it.text?.toString() == option }
        // A long menu can leave the option half-visible at its edge; measure it once it is fully on screen.
        target(item).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bounds = Rect().also(target(scrollTo { it.text?.toString() == option })::getBoundsInScreen)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("Menu option has a short touch target: $option", bounds.height() / density >= 48)
        screenshot?.let(::capture)
        activate(item)
        await { it.contentDescription?.toString() == label }
    }

    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = current.parent ?: error("Missing action for ${node.text}")
        return current
    }
    private fun activate(node: AccessibilityNodeInfo) {
        assertTrue(target(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        // A page-sized scroll can jump past a control at large text; look back as well as forward.
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(20) {
            val root = root()
            find(root, predicate)?.let { return it }
            find(root) { it.isScrollable && !it.isEditable }?.performAction(direction)
            instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        }
        error("Filter control not found: ${visibleText(root())}")
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(root(), predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected filter state not found: ${visibleText(root())}")
    }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun visibleText(node: AccessibilityNodeInfo?): String = if (node == null) "" else
        (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).map { visibleText(node.getChild(it)) }).joinToString(" | ")
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFilters") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "filter-options").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
