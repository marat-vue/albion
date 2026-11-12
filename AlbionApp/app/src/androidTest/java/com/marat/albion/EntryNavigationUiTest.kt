package com.marat.albion

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.ui.components.*
import com.marat.albion.ui.launch.LaunchScreen
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.games.*
import com.marat.albion.data.settings.PracticeOptions
import com.marat.albion.data.content.Deck
import com.marat.albion.data.library.BookPosition
import kotlinx.coroutines.flow.first
import android.os.Bundle
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/** Readiness, content priority, full text, real system scale, and persistent destination stacks. */
@RunWith(AndroidJUnit4::class)
class EntryNavigationUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private var reduced by mutableStateOf(true)
    private val density get() = activity!!.resources.displayMetrics.density
    private val destinations = listOf("Сегодня", "Учебник", "Чтение", "Практика", "Прогресс")

    @Before fun prepareWindow(): Unit = runBlocking {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        c.importer.importIfNeeded()
    }

    @Test fun readyContentNeverWaitsForTheDecorativeIntro() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (motion in listOf(false, true)) for (initiallyReady in listOf(true, false)) {
                reduced = motion
                var ready by mutableStateOf(initiallyReady)
                var finished = 0
                show(scenario, "launch-$motion-$initiallyReady", settle = 0) {
                    Text("Готовый интерфейс", Modifier.padding(top = 80.dp))
                    LaunchScreen(ready, "Проверяем материалы") { finished++ }
                }
                frames(2)
                if (!initiallyReady) {
                    assertEquals("Loading finished before readiness", 0, onMain { finished })
                    assertTrue(semantics().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Albion загружается") == true })
                    instrumentation.runOnMainSync { ready = true }
                    frames(2)
                }
                assertEquals("Ready content still waits for an intro or exit", 1, onMain { finished })
                assertFalse(semantics().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Albion загружается") == true })
                frames(2)
                assertEquals("Completion was delivered twice", 1, onMain { finished })
            }
            capture("ready-without-wait")
        }
    }

    @Test fun homeHeroPrecedesSessionAndOptionsRemainAvailable(): Unit = runBlocking {
        val original = c.settings.current()
        try {
            c.settings.setShortSessionAnswers(20)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                show(scenario, "home") { AlbionNavigation() }
                val greeting = com.marat.albion.ui.home.homeGreeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                await { it.text?.toString() == greeting }
                assertWholeText(text(greeting))
                assertFalse(fresh().any { it.text?.toString()?.contains("Ваш ритм") == true })
                capture("home-hero")
                val bar = navigationTop()
                val goal = bounds(await { it.text?.toString() == "цель дня" })
                assertTrue("Daily progress must follow the greeting", goal.top >= text(greeting).bounds.top)
                assertTrue("Daily progress requires scrolling", goal.height() > 0 && goal.bottom <= bar)
                val start = actionable(scrollTo {
                    it.text?.toString() == "Начать" && actionOrNull(it)?.let { action ->
                        val rect = bounds(action)
                        rect.height() + 1 >= 48 * density && rect.bottom <= bar
                    } == true
                })
                assertTarget(start)
                assertTrue("Start is covered by navigation: ${bounds(start)} / $bar", bounds(start).bottom <= bar)
                capture("home-start")
                click(scrollTo { it.text?.toString() == "Объём занятия" })
                click(scrollTo { it.text?.toString() == "5" && actionOrNull(it) != null })
                repeat(50) { if (c.settings.current().shortSessionAnswers != 5) SystemClock.sleep(50) }
                assertEquals(5, c.settings.current().shortSessionAnswers)
                scrollBack()
                click(scrollTo { it.text?.toString() == "Начать" && actionOrNull(it) != null })
                await { it.text?.toString() == "Показать ответ" }
                capture("started-session")
            }
        } finally { c.settings.setShortSessionAnswers(original.shortSessionAnswers) }
    }

    @Test fun librarySearchPrecedesContinuationAndFindsBooks(): Unit = runBlocking {
        c.library.syncBundled()
        val book = c.library.observeLibrary().first().first { it.book.title.contains("Keeper") }.book
        c.library.savePosition(book.id, BookPosition(0, 1, 0), .32f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "library") { AlbionNavigation() }
            selectDestination("Чтение")
            val search = await { it.isEditable }
            val rect = bounds(search)
            assertTrue("Search is below navigation", rect.bottom <= navigationTop())
            assertTrue("Search is outside the display", rect.top >= 0 && rect.height() >= 40 * density)
            val continuation = fresh().firstOrNull { it.isVisibleToUser && it.text?.toString() == "Продолжить" }
            if (continuation != null) assertTrue(rect.bottom <= bounds(continuation).top)
            capture("library-search-first")
            click(search)
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keeper")
            }))
            await { it.text?.toString() == book.title && actionOrNull(it) != null }
            assertEquals("Keeper", await { it.isEditable }.text?.toString())
            capture("library-query")
        }
    }

    @Test fun savedPracticeAndMistakesDoNotDisplaceTheCatalog(): Unit = runBlocking {
        c.settings.savePracticeOptions(Game.GRAMMAR_CHOICE.name, PracticeOptions(level = "B1", count = 20))
        val queue = c.study.buildQueue(Deck.WORDS)
        val card = (queue.learning + queue.reviews + queue.news).first()
        c.study.recordGame(card.id, Game.TYPE_ANSWER.name, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "practice") { AlbionNavigation() }
            selectDestination("Практика")
            await { it.contentDescription?.toString() == "Навык" }
            val tile = actionable(await { it.text?.toString() == Game.TYPE_ANSWER.title && actionOrNull(it) != null })
            assertTrue("The first exercise is hidden below navigation", bounds(tile).top < navigationTop())
            capture("practice-catalog-first")
            val disclosure = actionable(scrollTo { it.text?.toString() == "Продолжить" && actionOrNull(it)?.stateDescription?.toString() == "Свёрнуто" })
            assertEquals("Свёрнуто", disclosure.stateDescription?.toString())
            click(disclosure)
            scrollTo { it.text?.toString() == Game.GRAMMAR_CHOICE.title }
            assertWholeText(text(Game.GRAMMAR_CHOICE.title))
            assertEquals(20.sp, text(Game.GRAMMAR_CHOICE.title).layout.layoutInput.style.fontSize)
            capture("practice-continuation-title")
            val resume = scrollTo { it.text?.toString() == "Продолжить" && actionOrNull(it)?.let { a -> a.stateDescription == null } == true }
            assertWholeText(text("Продолжить", 15.sp))
            assertTarget(actionable(resume))
            capture("practice-continuation-action")
            click(resume)
            await { it.text?.toString() == "Начать" && actionOrNull(it) != null }
            capture("resumed-practice")
        }
    }

    @Test fun navigationKeepsDirectDestinationsAndAllFiveSectionsReachable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "destinations") { AlbionNavigation() }
            val more = fresh().firstOrNull { it.isVisibleToUser && it.text?.toString() == "Ещё" }
            val direct = if (more == null) destinations else listOf("Сегодня", "Практика", "Ещё")
            val original = direct.associateWith { bounds(actionable(await { n -> n.text?.toString() == it && actionOrNull(n) != null })) }
            val navFont = if (original.getValue("Сегодня").bottom < activity!!.resources.displayMetrics.heightPixels / 2) 12.5.sp else 11.sp
            for (label in direct) {
                val target = actionable(await { it.text?.toString() == label && actionOrNull(it) != null })
                assertTarget(target)
                assertWholeText(text(label, navFont))
            }
            capture("navigation-initial")
            for (label in destinations.reversed()) {
                selectDestination(label)
                val currentMore = fresh().firstOrNull { it.isVisibleToUser && it.text?.toString() == "Ещё" }
                if (currentMore != null && label !in listOf("Сегодня", "Практика")) {
                    assertEquals("Открыт раздел: $label", actionable(currentMore).stateDescription?.toString())
                    click(currentMore)
                    val target = actionable(await { it.text?.toString() == label && actionOrNull(it) != null })
                    assertTrue("Selected section is not announced", target.isSelected)
                    for (section in destinations) {
                        assertTarget(actionable(await { it.text?.toString() == section && actionOrNull(it) != null }))
                        assertWholeText(text(section, 12.5.sp))
                    }
                    capture("navigation-menu")
                    click(await { it.contentDescription?.toString() == "Закрыть разделы" })
                } else assertTrue(actionable(await { it.text?.toString() == label && actionOrNull(it) != null }).isSelected)
                for (section in direct) assertEquals("Navigation shifted after changing section", original.getValue(section),
                    bounds(actionable(await { it.text?.toString() == section && actionOrNull(it) != null })))
            }
            capture("navigation-after-switching")
        }
    }

    @Test fun switchingSectionsAndRecreationPreserveTheLibraryQuery(): Unit = runBlocking {
        c.library.syncBundled()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Use the real activity and saved navigation state for the recreation assertion.
            selectDestination("Чтение")
            val search = await { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keeper")
            }))
            selectDestination("Практика")
            selectDestination("Чтение")
            assertEquals("Keeper", await { it.isEditable }.text?.toString())
            scenario.recreate()
            assertEquals("Keeper", await { it.isEditable }.text?.toString())
        }
    }

    private fun actionOrNull(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        // Android omits the redundant click action on an already selected Role.Tab.
        while (current != null && !current.isClickable && !current.isSelected) current = current.parent
        return current
    }
    private fun actionable(node: AccessibilityNodeInfo) = actionOrNull(node) ?: error("Missing action")
    private fun fresh(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun selectDestination(label: String) {
        val direct = fresh().firstOrNull { it.isVisibleToUser && it.text?.toString() == label && actionOrNull(it) != null }
        if (direct != null) { if (!actionable(direct).isSelected) click(direct) } else {
            click(await { it.text?.toString() == "Ещё" })
            val target = await { it.text?.toString() == label && actionOrNull(it) != null }
            if (actionable(target).isSelected) click(await { it.contentDescription?.toString() == "Закрыть разделы" }) else click(target)
        }
    }
    private fun navigationTop(): Int {
        val target = actionable(await { it.text?.toString() == "Сегодня" && actionOrNull(it) != null })
        val rect = bounds(target)
        // A side rail leaves the entire content height available.
        return if (rect.bottom < activity!!.resources.displayMetrics.heightPixels / 2) activity!!.resources.displayMetrics.heightPixels else rect.top
    }
    private fun assertTarget(node: AccessibilityNodeInfo) {
        val r = bounds(node)
        assertTrue("Target smaller than 48 dp: $r", r.width() + 1 >= 48 * density && r.height() + 1 >= 48 * density)
        assertTrue("Target is clipped: $r", r.left >= 0 && r.top >= 0 && r.right <= activity!!.resources.displayMetrics.widthPixels && r.bottom <= activity!!.resources.displayMetrics.heightPixels)
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(30) {
            fresh().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            val container = fresh().firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                ?: error("No forward scroll container")
            assertTrue(container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            SystemClock.sleep(250)
        }
        error("Element missing after scrolling")
    }
    private fun scrollBack() {
        repeat(16) {
            val container = fresh().firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } } ?: return
            if (!container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            SystemClock.sleep(100)
        }
    }

    private fun frames(count: Int) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = activity!!.window.decorView
            fun next(left: Int) { view.postOnAnimation { if (left == 1) done.countDown() else next(left - 1) } }
            next(count)
        }
        assertTrue("No UI frame", done.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun semantics(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitNode(node: SemanticsNode) { result.add(node); node.children.forEach(::visitNode) }
        fun visitView(view: View) {
            if (view is ViewRootForTest) visitNode(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) for (i in 0 until view.childCount) visitView(view.getChildAt(i))
        }
        instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visitView)
            else visitView(activity!!.window.decorView)
        }
        return result
    }
    private fun show(scenario: ActivityScenario<MainActivity>, name: String, settle: Long = 700, content: @Composable () -> Unit) {
        scenario.onActivity { current ->
            activity = current
            val dark = InstrumentationRegistry.getArguments().getString("testDark") == "true"
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            current.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            current.setContent {
                CompositionLocalProvider(LocalAppContainer provides c, LocalReducedMotion provides reduced) {
                    key(name) { AlbionTheme(darkTheme = dark) {
                        MeasureNarrow(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    } }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(settle)
    }
    private data class Sample(val text: String, val layout: TextLayoutResult, val bounds: ComposeRect, val heading: Boolean)
    private fun text(value: String, fontSize: androidx.compose.ui.unit.TextUnit? = null): Sample {
        repeat(60) {
            val samples = mutableListOf<Sample>()
            fun visitNode(node: SemanticsNode) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { samples.add(Sample(it.layoutInput.text.text, it, node.boundsInRoot, node.config.contains(SemanticsProperties.Heading))) }
                node.children.forEach(::visitNode)
            }
            fun visitView(view: View) {
                if (view is ViewRootForTest) visitNode(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) for (i in 0 until view.childCount) visitView(view.getChildAt(i))
            }
            instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visitView)
            else visitView(activity!!.window.decorView)
        }
            samples.firstOrNull { it.text == value && (fontSize == null || it.layout.layoutInput.style.fontSize == fontSize) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Text layout not found: $value")
    }
    private fun assertWholeText(sample: Sample) {
        val result = sample.layout
        for (line in 0 until result.lineCount) {
            assertTrue("Line clipped: ${sample.text}; size=${result.size}; left=${result.getLineLeft(line)}; right=${result.getLineRight(line)}",
                result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
            assertTrue("Line height clipped: ${sample.text}; size=${result.size}; bottom=${result.getLineBottom(line)}; style=${result.layoutInput.style}",
                result.getLineBottom(line) <= result.size.height + 1f)
            assertFalse(result.isLineEllipsized(line))
            if (line > 0) {
                val offset = result.getLineStart(line)
                assertFalse("Word split: ${sample.text}", offset in 1 until sample.text.length && sample.text[offset - 1].isLetterOrDigit() && sample.text[offset].isLetterOrDigit())
            }
        }
        assertEquals(sample.text.length, result.getLineEnd(result.lineCount - 1, true))
    }
    private fun bounds(node: AccessibilityNodeInfo) = android.graphics.Rect().also(node::getBoundsInScreen)
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(100) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            SystemClock.sleep(100)
        }
        capture("missing-element")
        error("UI element not found")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("Missing click target")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        SystemClock.sleep(500)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureEntryNavigation") != "true") return
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "entry-and-navigation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
