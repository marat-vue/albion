package com.marat.albion

import android.app.UiAutomation
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.marat.albion.data.db.BookEntity
import com.marat.albion.data.grammar.MdCode
import com.marat.albion.data.grammar.MdParagraph
import com.marat.albion.data.library.BookPosition
import com.marat.albion.ui.components.*
import com.marat.albion.ui.grammar.*
import com.marat.albion.ui.library.LibraryScreen
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.settings.SettingsScreen
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingControlsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private var reduced by mutableStateOf(true)
    private val density get() = activity!!.resources.displayMetrics.density
    private val fixtures = mutableListOf<BookEntity>()

    @Before fun prepare(): Unit = runBlocking {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        c.importer.importIfNeeded()
        c.library.syncBundled()
    }
    @After fun cleanFixtures(): Unit = runBlocking { fixtures.forEach { c.library.delete(it.id) } }

    @Test fun settingsPreviewMatchesRealLessonAndRespondsToTheSizeControl(): Unit = runBlocking {
        val original = c.settings.current().grammarTextScale
        val sample = "Английский — маленькими шагами каждый день."
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (scale in listOf(.85f, 1f, 1.4f)) {
                    c.settings.setGrammarTextScale(scale)
                    show(scenario, "settings-$scale") { SettingsScreen({}) }
                    click(scrollTo { it.text?.toString() == "Чтение и учебник" })
                    val previewNode = scrollTo { it.text?.toString() == sample }
                    assertTrue(previewNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id))
                    SystemClock.sleep(400)
                    val preview = text(sample).layout
                    assertWholeText(text(sample))
                    assertEquals(AlbionFonts.Reading, preview.layoutInput.style.fontFamily)
                    capture("grammar-preview-$scale")
                    if (scale == 1f) {
                        click(scrollTo { it.contentDescription?.toString() == "Увеличить: Текст учебника" })
                        repeat(50) { if (c.settings.current().grammarTextScale == scale) SystemClock.sleep(50) }
                        assertTrue(c.settings.current().grammarTextScale > scale)
                        repeat(50) { if (text(sample).layout.layoutInput.style.fontSize == preview.layoutInput.style.fontSize) SystemClock.sleep(50) }
                        assertTrue(text(sample).layout.layoutInput.style.fontSize.value > preview.layoutInput.style.fontSize.value)
                    }
                    show(scenario, "lesson-$scale") {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            ProvideMdEnv(MdEnv(scale) {}) { MdBlockView(MdParagraph(sample)) }
                        }
                    }
                    val lesson = text(sample).layout
                    assertEquals("Preview typography differs from the lesson", lesson.layoutInput.style, preview.layoutInput.style)
                    assertWholeText(text(sample))
                }
            }
        } finally { c.settings.setGrammarTextScale(original) }
    }

    @Test fun codeScalesWithTheLessonAndKeepsLongLinesAndIndentationScrollable() {
        val code = listOf("if (ready) {", "    show(${"long_identifier_".repeat(12)})", "}").joinToString("\n")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var previousHeight = 0f
            for (scale in listOf(.85f, 1f, 1.4f)) {
                show(scenario, "code-$scale") {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        ProvideMdEnv(MdEnv(scale) {}) { MdBlockView(MdCode("kotlin", code)) }
                    }
                }
                val layout = text(code).layout
                assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
                assertEquals(13.sp * scale, layout.layoutInput.style.fontSize)
                assertEquals(20.sp * scale, layout.layoutInput.style.lineHeight)
                assertEquals("Code lines were wrapped or dropped", 3, layout.lineCount)
                assertWholeText(text(code))
                val lineHeight = layout.getLineBottom(1) - layout.getLineTop(1)
                assertTrue("Code line height did not grow", lineHeight > previousHeight)
                previousHeight = lineHeight
                val scroll = await { it.className?.toString() == "android.widget.HorizontalScrollView" }
                val range = semantics().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                    .config[SemanticsProperties.HorizontalScrollAxisRange]
                val before = onMain { range.value() }
                assertTrue("Code line cannot be scrolled", scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                SystemClock.sleep(400)
                assertTrue("Scrolling did not reveal more of the line", onMain { range.value() } > before)
                capture("scaled-code-$scale")
            }
        }
    }

    @Test fun shelfMenuCancelsAndDeletesOnlyTheChosenBookWithItsAnnotations(): Unit = runBlocking {
        val bundled = c.library.observeLibrary().first().filter { it.book.source == "asset" }.map { it.book.id }
        assertTrue(bundled.isNotEmpty())
        val book = fixture("Shelf")
        val other = fixture("Keep")
        val position = BookPosition(0, 0, 2)
        c.library.addBookmark(book.id, position, "A readable paragraph")
        c.library.addHighlight(book.id, 0, 0, 2, 10, "readable", "A fixture note")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val nav = navigator()
            show(scenario, "shelf") { LibraryScreen(nav) }
            await { it.text?.toString() == "Библиотека" }
            openDeleteMenu(book)
            assertEquals("Menu opened the reader", 1, onMain { nav.backStack.size })
            click(await { it.text?.toString() == "Отмена" })
            assertNotNull(c.library.book(book.id))
            assertEquals(1, c.library.observeBookmarks(book.id).first().size)
            assertEquals(1, c.library.observeHighlights(book.id).first().size)
            openDeleteMenu(book)
            capture("book-delete-confirmation")
            click(await { it.text?.toString() == "Удалить" })
            repeat(50) { if (c.library.book(book.id) != null) SystemClock.sleep(50) }
            assertNull(c.library.book(book.id))
            assertTrue(c.library.observeBookmarks(book.id).first().isEmpty())
            assertTrue(c.library.observeHighlights(book.id).first().isEmpty())
            assertNotNull(c.library.book(other.id))
            bundled.forEach { assertNotNull(c.library.book(it)) }
            assertEquals(1, onMain { nav.backStack.size })
        }
    }

    @Test fun searchOffersActionsForUserBooksAndKeepsBundledBooksWithoutDelete(): Unit = runBlocking {
        val book = fixture("Search")
        val bundled = c.library.observeLibrary().first().first { it.book.source == "asset" }.book
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val nav = navigator()
            show(scenario, "search") { LibraryScreen(nav) }
            setQuery(book.title)
            openDeleteMenu(book)
            assertEquals(1, onMain { nav.backStack.size })
            click(await { it.text?.toString() == "Отмена" })
            assertNotNull(c.library.book(book.id))
            capture("search-book-actions")
            setQuery(bundled.title)
            await { it.text?.toString() == bundled.title }
            instrumentation.uiAutomation.clearCache()
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any {
                it.contentDescription?.toString()?.startsWith("Действия с книгой") == true
            })
            assertNotNull(c.library.book(bundled.id))
            capture("bundled-book-without-delete")
        }
    }

    @Test fun continuationMenuKeepsTheReadingPositionAndAnnotationsOnCancel(): Unit = runBlocking {
        val book = fixture("Continue")
        val position = BookPosition(0, 0, 3)
        c.library.savePosition(book.id, position, .32f)
        c.library.addBookmark(book.id, position, "A readable paragraph")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val nav = navigator()
            show(scenario, "continuation") { LibraryScreen(nav) }
            await { it.text?.toString() == "Продолжить" }
            assertWholeText(text("Продолжить"))
            openDeleteMenu(book)
            assertEquals("Menu opened the reader", 1, onMain { nav.backStack.size })
            click(await { it.text?.toString() == "Отмена" })
            assertEquals(position, c.library.position(book.id))
            assertEquals(1, c.library.observeBookmarks(book.id).first().size)
            assertEquals(.32f, c.library.observeLibrary().first().first { it.book.id == book.id }.progress)
            capture("continuation-book-actions")
        }
    }

    private fun navigator() = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
    private suspend fun fixture(label: String): BookEntity {
        val unique = System.nanoTime()
        val file = File(instrumentation.targetContext.cacheDir, "ux9-$unique.md")
        val title = if (label == "Continue") "A book" else "UX $label $unique"
        file.writeText("# $title\nAuthor: Test\n\n## Chapter One\n\nA readable paragraph with enough words for the fixture $unique.")
        return try { c.library.import(Uri.fromFile(file)).also { fixtures.add(it) } } finally { file.delete() }
    }
    private fun openDeleteMenu(book: BookEntity) {
        val description = "Действия с книгой «${book.title}»"
        // A partially visible row is scrollable; measure its complete target after bringing it into view.
        val visible = target(scrollTo { it.contentDescription?.toString() == description })
        visible.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(300)
        val menu = target(await { it.contentDescription?.toString() == description })
        assertTarget(menu)
        click(menu)
        var remove = await { it.text?.toString() == "Удалить книгу" }
        // The menu scales in; measure its item once the popup has finished growing.
        for (attempt in 0 until 20) {
            SystemClock.sleep(100)
            val next = await { it.text?.toString() == "Удалить книгу" }
            val settled = bounds(target(next)) == bounds(target(remove))
            remove = next
            if (settled) break
        }
        assertTarget(target(remove))
        assertWholeText(text("Удалить книгу"))
        capture("visible-book-menu-${book.id.hashCode()}")
        click(remove)
        await { it.text?.toString() == "Удалить книгу?" }
    }
    private fun setQuery(query: String) {
        val search = await { it.isEditable }
        assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }
    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n = node
        while (!n.isClickable && !n.isCheckable && !n.isSelected && n.className?.toString() !in setOf("android.widget.Button", "android.widget.RadioButton") && n.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) n = n.parent ?: error("Missing target: ${node.text}")
        return n
    }
    private fun assertTarget(node: AccessibilityNodeInfo) {
        val r = bounds(node)
        assertTrue("Target below 48 dp: $r", r.width() + 1 >= 48 * density && r.height() + 1 >= 48 * density)
        assertTrue("Clipped action: $r", r.left >= 0 && r.top >= 0 && r.right <= activity!!.resources.displayMetrics.widthPixels && r.bottom <= activity!!.resources.displayMetrics.heightPixels)
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.uiAutomation.clearCache()
            val nodes = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            nodes.firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            val container = nodes.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            container?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        capture("history-missing")
        error("Element missing after scroll")
    }
    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Float) = if (v <= .04045f) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
            return .2126 * channel(c.red) + .7152 * channel(c.green) + .0722 * channel(c.blue)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    private fun distance(a: Int, b: Int) = listOf(0, 8, 16).sumOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) }
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
            samples.lastOrNull { it.text == value && (fontSize == null || it.layout.layoutInput.style.fontSize == fontSize) }?.let { return it }
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
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "reading-controls").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
