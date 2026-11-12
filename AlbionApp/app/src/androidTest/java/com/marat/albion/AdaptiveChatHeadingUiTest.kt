package com.marat.albion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.conversation.Scenario
import com.marat.albion.ui.components.ModuleHeading
import com.marat.albion.ui.components.SectionHeader
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.conversation.ChatComposer
import com.marat.albion.ui.conversation.ChatContext
import com.marat.albion.ui.conversation.ChatScreen
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.theme.AlbionTheme
import com.marat.albion.ui.theme.LocalReducedMotion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real windows: 320×740 dp / 200% and 411×890 dp / 100%, including the real keyboard. */
@RunWith(AndroidJUnit4::class)
class AdaptiveChatHeadingUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private val density get() = activity!!.resources.displayMetrics.density

    @Before fun prepareWindow() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @Test fun contextKeepsTheLevelWholeAndStatusOnItsOwnLine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "context") {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
                    ChatContext("Собеседование", "A2", "Загружаю модель…")
                }
            }
            val scenarioChip = text("Собеседование")
            val level = text("A2")
            val status = text("Загружаю модель…")
            assertWholeText(scenarioChip)
            assertWholeText(level)
            assertEquals("CEFR level wrapped", 1, level.layout.lineCount)
            assertTrue("Status competes with chips", status.bounds.top >= maxOf(scenarioChip.bounds.bottom, level.bounds.bottom))
            assertWholeText(status)
            capture("context-loading")
        }
    }

    @Test fun bothSectionHeadingsGiveTheTitlePriorityAndKeepItsFontSize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (module in listOf(false, true)) {
                show(scenario, "heading-$module") {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                        .verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                        if (module) {
                            ModuleHeading("01", "Словарный запас", "9 999 ответов")
                            ModuleHeading("02", "Прогноз", "14 дн")
                        } else {
                            SectionHeader("Словарный запас", trailing = "9 999 ответов")
                            SectionHeader("Прогноз", trailing = "14 дн")
                        }
                    }
                }
                val heading = text("Словарный запас")
                val count = text("9 999 ответов")
                assertWholeText(heading)
                assertWholeText(count)
                assertTrue("Section title is missing its heading role", heading.heading)
                assertEquals(com.marat.albion.ui.theme.Typography.titleLarge.fontSize, heading.layout.layoutInput.style.fontSize)
                assertFalse("Counter overlaps title", heading.bounds.overlaps(count.bounds))
                if (activity!!.resources.configuration.fontScale > 1.3f) {
                    assertTrue("Counter must yield the line to a long title", count.bounds.top >= heading.bounds.bottom)
                }
                val shortTitle = text("Прогноз")
                val shortCount = text("14 дн")
                assertWholeText(shortTitle)
                assertWholeText(shortCount)
                assertTrue("A short counter needlessly takes a new row", shortCount.bounds.left >= shortTitle.bounds.right)
                capture(if (module) "module-headings" else "section-headings")
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun composerKeepsHistoryVisibleAndSupportsLongDraftsAndEveryAction() {
        var draft by mutableStateOf("")
        var busy by mutableStateOf(false)
        var recording by mutableStateOf(false)
        var sent = ""
        var hints = 0
        var mic = 0
        var stops = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "composer") {
                BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                    val maxInputHeight = (maxHeight / 3).coerceIn(52.dp, 160.dp)
                    Column(Modifier.fillMaxSize()) {
                        TopBar({}, "Беседа")
                        if (!WindowInsets.isImeVisible) ChatContext("Собеседование", "A2")
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(24.dp)) {
                            item { Text("Tell me about your day.", style = MaterialTheme.typography.bodyLarge) }
                        }
                        ChatComposer(draft, { draft = it }, busy, recording, .5f, false, false, true,
                            onSend = { sent = draft; draft = "" }, onStop = { stops++ }, onHint = { hints++ }, onMic = { mic++ },
                            maxInputHeight = maxInputHeight)
                    }
                }
            }
            val empty = await { it.isEditable }
            assertTrue("The empty editor grows like a multiline placeholder", bounds(empty).height() <= 80 * density)
            assertTrue("Editor still loses too much width to its controls", bounds(empty).width() >= 150 * density)
            capture("composer-empty")
            click(await { it.contentDescription?.toString() == "Действия сообщения" })
            click(await { it.text?.toString() == "Подсказать ответ" })
            assertEquals(1, hints)
            click(await { it.contentDescription?.toString() == "Действия сообщения" })
            click(await { it.text?.toString() == "Сказать голосом" })
            assertEquals(1, mic)
            tap(await { it.isEditable })
            val longDraft = "Start of my answer. " + "This is a longer answer about my day. ".repeat(24) + "The final sentence."
            val field = await { it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, longDraft)
            }))
            repeat(40) {
                if (instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return@repeat
                SystemClock.sleep(100)
            }
            SystemClock.sleep(800)
            val keyboard = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: error("Keyboard must stay visible during editing")
            val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
            val edited = await { it.isEditable && it.text?.toString() == longDraft }
            assertTrue("Long draft displaced the conversation", bounds(edited).height() <= 160 * density)
            assertTrue("Editor is covered by the keyboard", bounds(edited).bottom <= keyboardBounds.top)
            val history = await { it.text?.toString() == "Tell me about your day." }
            assertTrue("No history remains above the editor", bounds(history).height() >= 20 * density && bounds(history).bottom <= bounds(edited).top)
            capture("composer-keyboard-long-draft")
            click(await { it.contentDescription?.toString() == "Отправить" })
            assertEquals(longDraft, sent)
            instrumentation.runOnMainSync { busy = true }
            click(await { it.contentDescription?.toString() == "Остановить ответ" })
            assertEquals(1, stops)
            instrumentation.runOnMainSync { busy = false; recording = true }
            await { it.contentDescription?.toString() == "Идёт запись. Пауза завершит её" }
            click(await { it.contentDescription?.toString() == "Закончить запись" })
            assertEquals(2, mic)
        }
    }

    @Test fun actualChatUsesTheAdaptiveContextAndCompactEditor(): Unit = runBlocking {
        val id = c.conversations.create(Scenario.INTERVIEW, "A2")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val navigator = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
                show(scenario, "actual-chat") { ChatScreen(id, navigator) }
                val level = text("A2")
                assertWholeText(level)
                assertEquals(1, level.layout.lineCount)
                val field = await { it.isEditable }
                assertTrue(bounds(field).height() <= 80 * density)
                capture("actual-chat")
            }
        } finally { c.conversations.delete(id) }
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
            assertTrue("Line clipped: ${sample.text}", result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
            assertTrue(result.getLineBottom(line) <= result.size.height + 1f)
            assertFalse(result.isLineEllipsized(line))
            if (line > 0) {
                val offset = result.getLineStart(line)
                assertFalse("Word split: ${sample.text}", offset in 1 until sample.text.length && sample.text[offset - 1].isLetterOrDigit() && sample.text[offset].isLetterOrDigit())
            }
        }
        assertEquals(sample.text.length, result.getLineEnd(result.lineCount - 1, true))
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
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
        if (InstrumentationRegistry.getArguments().getString("captureChatHeadings") != "true") return
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-chat-headings").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
