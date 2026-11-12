package com.marat.albion

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.BookEntity
import com.marat.albion.data.library.LibraryBook
import com.marat.albion.ui.components.*
import com.marat.albion.ui.deck.DictionaryScreen
import com.marat.albion.ui.deck.DeckStudyMetrics
import com.marat.albion.ui.library.ContinueReading
import com.marat.albion.ui.library.LibraryScreen
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.stats.StatsScreen
import com.marat.albion.ui.theme.AlbionTheme
import com.marat.albion.ui.theme.LocalReducedMotion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run against the real 320 dp / 200% and 411 dp / 100% windows. No synthetic font density. */
@RunWith(AndroidJUnit4::class)
class AdaptiveReadingStatsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null

    @Before fun prepareWindow() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @Test fun headerKeepsTheFullTitleAtTheSystemFontSize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "header") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                    ScreenHeader("Библиотека", compact = true, trailing = {
                        Row {
                            RoundIconButton(AlbionIcons.Add, "Добавить книгу", {})
                            SettingsShortcut({})
                        }
                    })
                }
            }
            val title = layout("Библиотека")
            assertUnclipped("Библиотека", title)
            assertEquals("The heading must not cancel the system text scale", 28.sp, title.layoutInput.style.fontSize)
            for (label in listOf("Добавить книгу", "Настройки")) {
                val button = await { it.contentDescription?.toString() == label }
                var target = button
                while (!target.isClickable) target = target.parent ?: error("Missing header action")
                val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
                assertTrue("Header action is squeezed", bounds.width() >= 48 * activity!!.resources.displayMetrics.density)
            }
            capture("header")
        }
    }

    @Test fun continueReadingKeepsWholeWordsAndItsOpenAction(): Unit = runBlocking {
        val title = "The Keeper of Gull Island — A Journey Across the Northern Coast"
        val book = LibraryBook(BookEntity("layout-book", title, "Alex Morgan", "B1", "asset", 0, 1, 100, 0), .37f, 1)
        var opened = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "continue") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(gutter)) {
                    ContinueReading(book, Modifier) { opened = true }
                }
            }
            assertEquals(1, layout("Продолжить").lineCount)
            assertWholeWords(title)
            assertUnclipped(title, layout(title))
            capture("continue-title")
            var button = scrollTo { it.text?.toString() == "Читать" }
            while (!button.isClickable) button = button.parent ?: error("Missing reading action")
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue("Reading action was lost after reflow", opened)
            capture("continue-action")
        }
    }

    @Test fun metricsKeepWholeWordsAndFourDigitValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "metrics") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatPair(StatMetric(9999, "ответов сегодня"), StatMetric(100, "минут сегодня"))
                    StatPair(StatMetric(92, "вспомнили · 30 дней", "%"), StatMetric(1234, "ответов за всё время"))
                    BoundVolume { DeckStudyMetrics(4567, 999) }
                }
            }
            for (value in listOf("9999", "100", "92%", "1234", "4567", "999")) {
                val text = layout(value)
                assertUnclipped(value, text)
                assertEquals("Value wrapped: $value", 1, text.lineCount)
            }
            for (label in listOf("ответов сегодня", "минут сегодня", "вспомнили · 30 дней", "ответов за всё время", "к повторению", "новых сегодня")) {
                assertWholeWords(label)
            }
            capture("metrics-top")
            scrollTo { it.text?.toString() == "новых сегодня" }
            capture("metrics-bottom")
        }
    }

    @Test fun actualLibraryStatsAndDeckScreensRemainUsable(): Unit = runBlocking {
        c.importer.importIfNeeded()
        c.library.syncBundled()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun nav() = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
            show(scenario, "library") { LibraryScreen(nav()) }
            assertUnclipped("Библиотека", layout("Библиотека"))
            val settings = await { it.contentDescription?.toString() == "Настройки" }
            val settingsBounds = android.graphics.Rect().also(settings::getBoundsInScreen)
            assertTrue("Library settings are outside the window: $settingsBounds", settingsBounds.right <= activity!!.resources.displayMetrics.widthPixels && settingsBounds.width() > 0)
            SystemClock.sleep(1000)
            capture("library")
            show(scenario, "stats") { StatsScreen(nav()) }
            scrollTo { it.text?.toString() == "ответов сегодня" }
            assertWholeWords("ответов сегодня")
            scrollTo { it.text?.toString() == "ответов за всё время" }
            assertWholeWords("вспомнили · 30 дней")
            capture("stats")
            show(scenario, "deck") { DictionaryScreen(Deck.WORDS, nav()) }
            scrollTo { it.text?.toString() == "новых сегодня" }
            assertWholeWords("к повторению")
            assertWholeWords("новых сегодня")
            capture("deck")
        }
    }

    private fun show(scenario: ActivityScenario<MainActivity>, name: String, content: @Composable () -> Unit) {
        scenario.onActivity { current ->
            activity = current
            current.setContent {
                CompositionLocalProvider(LocalAppContainer provides c, LocalReducedMotion provides true) {
                    key(name) { AlbionTheme {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    } }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        capture("$name-initial")
    }

    private fun layouts(): List<TextLayoutResult> {
        val result = mutableListOf<TextLayoutResult>()
        fun semantics(node: SemanticsNode) {
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            node.children.forEach(::semantics)
        }
        fun visit(view: View) {
            if (view is ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        instrumentation.runOnMainSync { visit(activity!!.window.decorView) }
        return result
    }
    private fun layout(text: String): TextLayoutResult {
        repeat(50) {
            layouts().firstOrNull { it.layoutInput.text.text == text }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing text layout: $text")
    }
    private fun describe(result: TextLayoutResult) = "size=${result.size} constraints=${result.layoutInput.constraints} lines=${result.lineCount} font=${result.layoutInput.style.fontSize} widthOverflow=${result.didOverflowWidth} heightOverflow=${result.didOverflowHeight}"
    private fun assertUnclipped(text: String, result: TextLayoutResult) {
        // Paragraph width can include unused space; check each rendered line, allowing pixel rounding.
        for (line in 0 until result.lineCount) {
            assertTrue("Text clipped: $text ${describe(result)} line=${result.getLineRight(line)}", result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
            assertTrue("Line clipped vertically: $text", result.getLineBottom(line) <= result.size.height + 1f)
        }
        assertEquals("Characters omitted: $text", text.length, result.getLineEnd(result.lineCount - 1, visibleEnd = true))
        for (line in 0 until result.lineCount) assertFalse("Text ellipsized: $text", result.isLineEllipsized(line))
    }
    private fun assertWholeWords(text: String) {
        val result = layout(text)
        assertUnclipped(text, result)
        for (line in 1 until result.lineCount) {
            val offset = result.getLineStart(line)
            assertFalse("Word broken inside a line: $text", offset in 1 until text.length && text[offset - 1].isLetterOrDigit() && text[offset].isLetterOrDigit())
        }
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun fresh(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(100) {
            fresh().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element not found")
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(30) {
            val nodes = fresh()
            nodes.firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            if (!nodes.filter { it.isVisibleToUser && it.isScrollable }.any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }) {
                SystemClock.sleep(200)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
        }
        error("Action not reachable by scrolling: ${fresh().mapNotNull { it.text }.joinToString()}")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureReadingStats") != "true") return
        SystemClock.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-reading-stats").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
