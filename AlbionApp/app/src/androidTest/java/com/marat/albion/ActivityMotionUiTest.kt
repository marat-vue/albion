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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.ui.components.*
import com.marat.albion.ui.conversation.PartnerBubble
import com.marat.albion.ui.stats.ActivityCalendar
import com.marat.albion.ui.study.SwipeToRate
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real dates, accessibility, live preference changes, frame geometry, and intentional gestures. */
@RunWith(AndroidJUnit4::class)
class ActivityMotionUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private var reduced by mutableStateOf(true)
    private val density get() = activity!!.resources.displayMetrics.density
    private val endDay = LocalDate.of(2026, 11, 4).toEpochDay()

    @Before fun prepareWindow() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @Test fun calendarSelectsRealDatesAndUpdatesTheExactCount() {
        var counts by mutableStateOf(List(30) { it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "calendar") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(gutter)) { ActivityCalendar(counts, endDay) }
            }
            assertWholeText(text("4 ноября 2026 — 29 ответов"))
            click(await { it.contentDescription?.toString() == "12 октября 2026 — 6 ответов" })
            assertWholeText(text("12 октября 2026 — 6 ответов"))
            // Compose exposes Selected as Android's checked state for roles other than Tab.
            var chosen = await { it.contentDescription?.toString() == "12 октября 2026 — 6 ответов" }
            // Content descriptions can be exposed as children of the actionable day node.
            while (!chosen.isClickable) chosen = chosen.parent ?: error("Missing day button")
            val selected = semantics().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() == "12 октября 2026 — 6 ответов" }
            assertEquals(true, selected.config.getOrNull(SemanticsProperties.Selected))
            assertTrue("Selected day: $chosen", chosen.isChecked)
            assertEquals("Выбрано", chosen.stateDescription?.toString())
            instrumentation.runOnMainSync { counts = counts.toMutableList().apply { this[6] = 42 } }
            assertWholeText(text("12 октября 2026 — 42 ответа"))
            capture("calendar-selected")
            click(await { it.contentDescription?.toString() == "6 октября 2026 — 0 ответов" })
            assertWholeText(text("6 октября 2026 — 0 ответов"))
        }
    }

    @Test fun calendarHasAccessibleDatesFixedBandsAndLargeTargets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "calendar-grid") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(gutter)) { ActivityCalendar(List(30) { if (it == 10) 57 else it }, endDay) }
            }
            for (label in listOf("0", "1–9", "10–24", "25–49", "50+")) assertWholeText(text(label))
            val cells = semantics().filter { it.config.getOrNull(SemanticsProperties.CollectionItemInfo) != null }
            assertEquals("Every day must be accessible", 30, cells.size)
            for (cell in cells) {
                assertTrue("Day target too narrow", cell.size.width + 1 >= 48 * density)
                assertTrue("Day target too short", cell.size.height + 1 >= 48 * density)
                assertNotNull(cell.config.getOrNull(SemanticsActions.OnClick))
            }
            val first = cells.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() == "6 октября 2026 — 0 ответов" }
            val position = first.config.getOrNull(SemanticsProperties.CollectionItemInfo)!!
            assertEquals(0, position.rowIndex)
            assertEquals("October 6, 2026 is Tuesday", 1, position.columnIndex)
            val last = cells.first { it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("Сегодня") == true }
            assertTrue(last.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().startsWith("4 ноября 2026"))
            val horizontal = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id }
            }
            if (horizontal != null) {
                assertTrue(horizontal.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
                instrumentation.waitForIdleSync()
                capture("calendar-scrolled")
            }
        }
    }

    @Test fun actualPreferenceStopsAnInFlightMotionAndSnapsNewValues(): Unit = runBlocking {
        val original = c.settings.current()
        var target by mutableFloatStateOf(0f)
        var observed = 0f
        var currentReduced = false
        try {
            c.settings.setReduceMotion(false)
            reduced = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                show(scenario, "preference") {
                    val settings by c.settings.settings.collectAsState(initial = original.copy(reduceMotion = false))
                    AlbionExperience(settings) {
                        currentReduced = LocalReducedMotion.current
                        val value by quietFloatAsState(target, tween(1500), label = "test-position")
                        observed = value
                        Text("Движение", Modifier.padding(top = 90.dp).graphicsLayer { translationX = value })
                    }
                }
                instrumentation.runOnMainSync { target = 100f }
                frames(4)
                val moving = onMain { observed }
                assertTrue("Normal animation did not run: $moving", moving > 0f && moving < 100f)
                c.settings.setReduceMotion(true)
                repeat(80) { if (!onMain { currentReduced }) SystemClock.sleep(25) }
                assertTrue(onMain { currentReduced })
                frames(2)
                assertEquals("Existing motion did not stop", 100f, onMain { observed }, .001f)
                instrumentation.runOnMainSync { target = 0f }
                frames(2)
                assertEquals("New motion did not snap", 0f, onMain { observed }, .001f)
                capture("motion-preference")
            }
        } finally { c.settings.setReduceMotion(original.reduceMotion) }
    }

    @Test fun streamingBubbleGrowthAndListReorderSnapWithReducedMotion() {
        var reply by mutableStateOf("A short reply.")
        var order by mutableStateOf(listOf("Item A", "Item B", "Item C"))
        val longer = "A longer answer with enough words to wrap onto several lines in this conversation."
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "live-layout") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(gutter)) {
                    PartnerBubble(reply)
                    Text("After reply", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f)) {
                        items(order, key = { it }) { Text(it, quietItem().fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            instrumentation.runOnMainSync { reply = longer; order = order.reversed() }
            frames(2)
            val immediate = listOf("After reply", "Item A", "Item C").map { text(it).bounds }
            SystemClock.sleep(700)
            val settled = listOf("After reply", "Item A", "Item C").map { text(it).bounds }
            immediate.zip(settled).forEach { (a, b) -> assertEquals("Reduced layout continued moving", a.top, b.top, 1f) }
            assertTrue(text("Item C").bounds.top < text("Item A").bounds.top)
            capture("streaming-and-list")
            // The same production component still animates in normal mode.
            instrumentation.runOnMainSync { reduced = false; reply = "A short reply." }
            SystemClock.sleep(700)
            instrumentation.runOnMainSync { reply = longer }
            frames(2)
            val animatedTop = text("After reply").bounds.top
            SystemClock.sleep(700)
            val finalTop = text("After reply").bounds.top
            assertTrue("Normal bubble growth was unexpectedly disabled", finalTop > animatedTop + 1f)
        }
    }

    @Test fun disclosureAndWaitingIndicatorsStayStaticAndUsable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "static-components") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(gutter)) {
                    Disclosure("Подробности") { Text("Содержимое блока", style = MaterialTheme.typography.bodyMedium) }
                    Text("After disclosure", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "Ожидание без движения" },
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuietCircularProgressIndicator()
                        QuietLinearProgressIndicator()
                    }
                }
            }
            var target = await { it.text?.toString() == "Подробности" }
            while (!target.isClickable) target = target.parent ?: error("Disclosure action missing")
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            frames(2)
            val immediate = text("After disclosure").bounds.top
            assertWholeText(text("Содержимое блока"))
            val region = bounds(await { it.contentDescription?.toString() == "Ожидание без движения" })
            val before = crop(region)
            SystemClock.sleep(600)
            assertEquals(immediate, text("After disclosure").bounds.top, 1f)
            val after = crop(region)
            assertTrue("Reduced waiting indicator is still animating", before.sameAs(after))
            before.recycle(); after.recycle()
            capture("static-components")
        }
    }

    @Test fun revealedCardDoesNotDemonstrateASwipeAndBothGesturesStillWork() {
        var enabled by mutableStateOf(false)
        val ratings = mutableListOf<Rating>()
        reduced = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "swipe") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(gutter)) {
                    Box(Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = "Учебная карточка" }) {
                        SwipeToRate(enabled, { ratings.add(it) }) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("Ответ", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
            instrumentation.runOnMainSync { enabled = true }
            frames(2)
            val initial = text("Ответ").bounds
            repeat(20) {
                SystemClock.sleep(100)
                assertEquals("Unrequested card demonstration moved the answer", initial, text("Ответ").bounds)
            }
            assertTrue(ratings.isEmpty())
            capture("stationary-answer")
            val card = bounds(await { it.contentDescription?.toString() == "Учебная карточка" })
            drag(card, .30f, .85f)
            SystemClock.sleep(500)
            assertEquals(listOf(Rating.Good), ratings)
            instrumentation.runOnMainSync { reduced = true }
            frames(2)
            drag(card, .70f, .15f)
            SystemClock.sleep(200)
            assertEquals(listOf(Rating.Good, Rating.Again), ratings)
            assertEquals(initial, text("Ответ").bounds)
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
    private fun crop(rect: android.graphics.Rect): Bitmap {
        val whole = instrumentation.uiAutomation.takeScreenshot()!!
        return Bitmap.createBitmap(whole, rect.left, rect.top, rect.width(), rect.height()).also { whole.recycle() }
    }
    private fun drag(rect: android.graphics.Rect, from: Float, to: Float) {
        val start = SystemClock.uptimeMillis()
        // Like a real finger, lift at the last reported position: Compose ignores movement carried by ACTION_UP.
        for (i in 0..13) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 13 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val x = rect.left + rect.width() * (from + (to - from) * minOf(i, 12) / 12)
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, rect.exactCenterY(), 0)
            instrumentation.sendPointerSync(event); event.recycle()
            SystemClock.sleep(if (i == 12) 100 else 16)
        }
    }
    private fun semantics(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitNode(node: SemanticsNode) { result.add(node); node.children.forEach(::visitNode) }
        fun visitView(view: View) {
            if (view is ViewRootForTest) visitNode(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) for (i in 0 until view.childCount) visitView(view.getChildAt(i))
        }
        instrumentation.runOnMainSync { visitView(activity!!.window.decorView) }
        return result
    }
    private fun show(scenario: ActivityScenario<MainActivity>, name: String, content: @Composable () -> Unit) {
        scenario.onActivity { current ->
            activity = current
            val dark = InstrumentationRegistry.getArguments().getString("testDark") == "true"
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            current.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            current.setContent {
                CompositionLocalProvider(LocalAppContainer provides c, LocalReducedMotion provides reduced) {
                    key(name) { AlbionTheme(darkTheme = dark) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    } }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
    }
    private data class Sample(val text: String, val layout: TextLayoutResult, val bounds: ComposeRect, val heading: Boolean)
    private fun text(value: String): Sample {
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
            instrumentation.runOnMainSync { visitView(activity!!.window.decorView) }
            samples.firstOrNull { it.text == value }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Text layout not found: $value")
    }
    private fun assertWholeText(sample: Sample) {
        val result = sample.layout
        for (line in 0 until result.lineCount) {
            assertTrue("Line clipped: ${sample.text}; size=${result.size}; left=${result.getLineLeft(line)}; right=${result.getLineRight(line)}",
                result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
            assertTrue(result.getLineBottom(line) <= result.size.height + 1f)
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
        if (InstrumentationRegistry.getArguments().getString("captureActivityMotion") != "true") return
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "activity-and-motion").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
