package com.marat.albion

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.settings.ThemeMode
import com.marat.albion.ui.components.*
import com.marat.albion.ui.grammar.*
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.study.CardAudio
import com.marat.albion.ui.study.CardFront
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks actual text geometry at system font scales, plus Android's font choice for the IPA corpus. */
@RunWith(AndroidJUnit4::class)
class AdaptiveTypographyUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null

    @Before fun prepareWindow() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @Test fun segmentsKeepFullLabelsAndEverySelection() {
        var theme by mutableIntStateOf(0)
        var count by mutableIntStateOf(0)
        val labels = ThemeMode.entries.map { it.title }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "segments") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Тема приложения", style = MaterialTheme.typography.titleMedium)
                    SegmentedPicker(labels, theme, { theme = it })
                    Box(Modifier.background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.medium).padding(12.dp)) {
                        SegmentedPicker(listOf("10", "20", "40"), count, { count = it }, inverse = true)
                    }
                }
            }
            for (label in labels + listOf("10", "20", "40")) {
                val sample = text(label)
                assertWholeText(sample)
                assertEquals("Selection label was shrunk", 15.sp, sample.layout.layoutInput.style.fontSize)
            }
            val system = text(labels[0])
            val light = text(labels[1])
            if (activity!!.resources.configuration.fontScale > 1.3f) {
                assertTrue("Long choices must stack", light.bounds.top >= system.bounds.bottom)
            }
            assertTrue("Short numeric choices should remain inline", text("40").bounds.left > text("10").bounds.right)
            click(await { it.text?.toString() == labels[2] })
            click(await { it.text?.toString() == "40" })
            assertEquals(2, theme)
            assertEquals(2, count)
            capture("segments")
        }
    }

    @Test fun grammarLevelsScrollWithoutShrinkingAndThreeDigitNumbersGrow(): Unit = runBlocking {
        val stages = grammarStages(c.grammar.book())
        assertEquals(5, stages.size)
        var active by mutableStateOf(stages.first().level)
        val topic = stages.first().topics.first().copy(number = 109)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "grammar-atlas") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                    GrammarAtlas(stages, active, emptyMap(), 0, 0, { active = it })
                    GrammarRouteLesson(topic, null, true, false, true, true, null, 0, {})
                }
            }
            for (stage in stages) {
                val level = text(stage.level)
                val count = text("0/${stage.topics.size}")
                assertWholeText(level)
                assertWholeText(count)
                assertEquals(16.sp, level.layout.layoutInput.style.fontSize)
                assertEquals(12.5.sp, count.layout.layoutInput.style.fontSize)
                assertEquals(1, count.layout.lineCount)
            }
            val number = text("109")
            assertWholeText(number)
            assertEquals(12.5.sp, number.layout.layoutInput.style.fontSize)
            assertWholeText(text(topic.title))
            capture("grammar-atlas-start")
            val target = stages.last().level
            repeat(8) {
                if (visible(target)) return@repeat
                val scroller = await { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id } }
                assertTrue(scroller.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
                SystemClock.sleep(300)
            }
            click(await { it.text?.toString() == target })
            assertEquals(target, active)
            assertWholeText(text(target))
            capture("grammar-atlas-end")
        }
    }

    @Test fun informationalLabelsAndCardTranscriptionsKeepTheirDesignFonts(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val card = c.database.cards().all().first { it.ipa.contains('ː') && it.term.length <= 8 }
        val audio = object : CardAudio {
            override val playingKey: String? = null
            override fun play(deck: Deck, file: String?, text: String, key: String) {}
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "labels-ipa") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                    SignalLabel("Микрофон активен")
                    SignalLabel("Слушайте → произнесите", Modifier.padding(top = 12.dp))
                    Text("/ˈθɜːti ʃɪp ɑː ʊ ð ʒ ŋ ə æ ɒ ʌ ɡ t͡ʃ/", style = IpaStyle, modifier = Modifier.padding(top = 12.dp))
                    CardFront(card, null, audio, true)
                }
            }
            for (label in listOf("Микрофон активен", "Слушайте → произнесите")) {
                val sample = text(label)
                assertWholeText(sample)
                assertEquals(12.5.sp, sample.layout.layoutInput.style.fontSize)
                assertEquals(AlbionFonts.Tech, sample.layout.layoutInput.style.fontFamily)
            }
            val ipa = text(card.ipa)
            assertWholeText(ipa)
            assertEquals(AlbionFonts.Ipa, ipa.layout.layoutInput.style.fontFamily)
            assertEquals(16.sp, ipa.layout.layoutInput.style.fontSize)
            capture("labels-and-ipa")
        }
    }

    @Test fun actualGrammarTitleKeepsItsSizeAndActions(): Unit = runBlocking {
        val nav = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "grammar-screen") { GrammarScreen(nav) }
            val title = text("Учебник")
            assertWholeText(title)
            assertEquals(com.marat.albion.ui.theme.Typography.displaySmall.fontSize, title.layout.layoutInput.style.fontSize)
            assertTrue(title.heading)
            val large = activity!!.resources.configuration.fontScale > 1.3f
            for (label in if (large) listOf("Поиск", "Меню учебника") else listOf("Справочники", "Настройки")) {
                val node = await { it.contentDescription?.toString() == label }
                var target = node
                while (!target.isClickable) target = target.parent ?: error("Missing action")
                assertTrue(bounds(target).width() >= 48 * activity!!.resources.displayMetrics.density)
            }
            click(await { it.contentDescription?.toString() == if (large) "Меню учебника" else "Справочники" })
            if (large) await { it.text?.toString() == "Справочники и курс" }
            else await { it.text?.toString()?.contains("Справочники") == true }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { it.text?.toString() == "A1" }
            capture("grammar-screen")
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun ipaCorpusUsesOnlyTheBundledFontWithoutMissingGlyphs(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val cards = c.database.cards().all()
        val transcriptions = buildSet {
            cards.forEach { card ->
                if (card.ipa.isNotBlank()) add(card.ipa)
                card.extra?.let { extra ->
                    runCatching { c.json.decodeFromString<IrregularData>(extra) }.getOrNull()?.forms?.forEach { form ->
                        form.variants.forEach { if (it.ipa.isNotBlank()) add(it.ipa) }
                    }
                }
            }
            add("/ˈθɜːti ʃɪp ɑː ʊ ð ʒ ŋ ə æ ɒ ʌ ɡ t͡ʃ/")
        }
        assertTrue("Corpus unexpectedly empty", transcriptions.size > 1000)
        val font = Font.Builder(instrumentation.targetContext.resources, R.font.noto_sans).build()
        val paint = Paint().apply {
            typeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
            textSize = 48f
        }
        val usedFonts = mutableSetOf<Font>()
        var glyphCount = 0
        for (ipa in transcriptions) {
            val value = ipa.replace(Regex("\\s+"), " ")
            val shaped = TextRunShaper.shapeTextRun(value, 0, value.length, 0, value.length, 0f, 0f, false, paint)
            for (i in 0 until shaped.glyphCount()) {
                assertTrue("Missing IPA glyph: $ipa", shaped.getGlyphId(i) != 0)
                usedFonts.add(shaped.getFont(i))
                glyphCount++
            }
        }
        for (used in usedFonts) assertEquals("A system fallback font was used", font.buffer.duplicate(), used.buffer.duplicate())
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-typography").apply { mkdirs() }
        File(dir, "ipa-coverage.txt").writeText("Transcriptions: ${transcriptions.size}\nGlyphs: $glyphCount\nFonts: ${usedFonts.size}\nMissing glyphs: 0\nSystem fallback fonts: 0\n")
    }

    private fun visible(value: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow).any {
            it.isVisibleToUser && it.text?.toString() == value && bounds(it).width() > 0
        }
    }
    private fun show(scenario: ActivityScenario<MainActivity>, name: String, content: @Composable () -> Unit) {
        scenario.onActivity { current ->
            activity = current
            val dark = InstrumentationRegistry.getArguments().getString("testDark") == "true"
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            current.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            current.setContent {
                CompositionLocalProvider(LocalAppContainer provides c, LocalReducedMotion provides true) {
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
        if (InstrumentationRegistry.getArguments().getString("captureTypography") != "true") return
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-typography").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
