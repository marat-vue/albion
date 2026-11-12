package com.marat.albion

import android.app.UiAutomation
import android.graphics.Bitmap
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.marat.albion.ui.components.*
import com.marat.albion.ui.conversation.ConversationsScreen
import com.marat.albion.data.conversation.Scenario
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SelectionAndConfirmationUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private var reduced by mutableStateOf(true)
    private val density get() = activity!!.resources.displayMetrics.density

    @Before fun prepare(): Unit = runBlocking {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        c.importer.importIfNeeded()
    }

    @Test fun choosingAnOptionKeepsSelectionAndConsistentReadableColours() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var selected by mutableIntStateOf(0)
            var content = Color.Unspecified
            var container = Color.Unspecified
            var border = Color.Unspecified
            var background = Color.Unspecified
            show(scenario, "selection") {
                content = MaterialTheme.colorScheme.selectedContent
                container = MaterialTheme.colorScheme.selectedContainer
                border = MaterialTheme.colorScheme.selectedBorder
                background = MaterialTheme.colorScheme.background
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionPicker("Режим", listOf("Первый", "Второй"), selected, { selected = it })
                    ChoiceRow("Первый выбор", selected == 0, { selected = 0 })
                    ChoiceRow("Второй выбор", selected == 1, { selected = 1 })
                    Text("Открытая строка", Modifier.fillMaxWidth().selectedRow(true).padding(16.dp), color = content)
                }
            }
            assertTrue("Selected text contrast", contrast(content, container) >= 4.5)
            assertTrue("Selected boundary contrast", contrast(border, background) >= 3.0)
            assertEquals(content, text("Первый выбор").layout.layoutInput.style.color)
            click(await { it.contentDescription?.toString() == "Режим" })
            val first = target(await { it.text?.toString() == "Первый" })
            assertTrue("Selected menu item not announced", first.isSelected || first.isChecked)
            assertEquals(content, text("Первый").layout.layoutInput.style.color)
            assertWholeText(text("Первый"))
            capture("selected-menu")
            click(await { it.text?.toString() == "Второй" })
            assertEquals(1, onMain { selected })
            assertTrue(target(await { it.text?.toString() == "Второй выбор" }).isChecked)
            assertEquals(content, text("Второй выбор").layout.layoutInput.style.color)
            assertWholeText(text("Второй выбор"))
            capture("selected-controls")
        }
    }

    @Test fun confirmationsKeepCancelAndDestructiveActionsVisibleWithLongConsequences() {
        val cases = listOf(
            "Удалить книгу?" to "«A very long book title about learning English» — книга, закладки, выделения и заметки будут удалены. Отменить удаление нельзя.",
            "Удалить беседу?" to "Все реплики и разборы этой беседы будут удалены. Отменить удаление нельзя.",
            "Удалить модель распознавания?" to "Беседы сохранятся, писать сообщения можно и дальше. Для голосового ввода модель потребуется установить снова.",
            "Удалить модель перевода?" to "Словарь, книги и карточки сохранятся. Для перевода фрагментов модель потребуется установить снова.",
            "Сбросить прогресс?" to "Все карточки колоды снова станут новыми. Расписание повторений будет сброшено, статистика занятий сохранится. Отменить сброс нельзя.",
            "Удалить весь прогресс учебника?" to "Будут удалены отметки чтения и освоения, закладки, последние результаты проверок и расписание повторения тем. Отменить удаление нельзя.",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((index, data) in cases.withIndex()) {
                var confirms = 0
                var dismissals = 0
                var visible by mutableStateOf(true)
                var danger = Color.Unspecified
                val label = if (data.first.startsWith("Сбросить")) "Сбросить" else "Удалить"
                show(scenario, "confirm-$index") {
                    danger = MaterialTheme.colorScheme.error
                    if (visible) DestructiveConfirmationDialog(data.first, data.second,
                        { confirms++; visible = false }, { dismissals++; visible = false }, confirmLabel = label)
                }
                for (action in listOf(label, "Отмена")) {
                    val button = target(await { it.text?.toString() == action })
                    assertTarget(button)
                    assertTrue(button.isEnabled)
                    assertWholeText(text(action))
                }
                assertEquals(danger, text(label).layout.layoutInput.style.color)
                assertTrue("Dialog title missing heading", text(data.first).heading)
                assertWholeText(text(data.first))
                capture("confirmation-$index")
                click(await { it.text?.toString() == "Отмена" })
                assertEquals(0, confirms)
                assertEquals(1, dismissals)
                instrumentation.runOnMainSync { visible = true }
                click(await { it.text?.toString() == label })
                assertEquals(1, confirms)
                assertEquals(1, dismissals)
            }
        }
    }

    @Test fun busyConfirmationBlocksDuplicateActionsAndRetryPreservesConsequences() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var busy by mutableStateOf(false)
            var failure by mutableStateOf<String?>(null)
            var confirmations = 0
            var dismissals = 0
            val message = "Будут сняты только отметки «Прочитано». Освоение, закладки, результаты проверок и план повторения сохранятся. Отменить сброс нельзя."
            show(scenario, "busy") {
                DestructiveConfirmationDialog("Сбросить отметки чтения?", message,
                    { confirmations++; busy = true }, { dismissals++ }, confirmLabel = "Сбросить", busyLabel = "Сбрасываем…", busy = busy, errorMessage = failure)
            }
            click(await { it.text?.toString() == "Сбросить" })
            val pending = target(await { it.text?.toString() == "Сбрасываем…" })
            assertFalse(pending.isEnabled)
            assertTarget(pending)
            assertFalse(target(await { it.text?.toString() == "Отмена" }).isEnabled)
            assertWholeText(text("Сбрасываем…"))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(250)
            assertEquals(0, dismissals)
            assertEquals(1, confirmations)
            instrumentation.runOnMainSync { busy = false; failure = "Не удалось выполнить сброс. Попробуйте ещё раз." }
            await { it.text?.toString() == "Сбросить" }
            assertEquals(message, text(message).text)
            assertWholeText(text(message))
            assertWholeText(text(failure!!))
            scrollTo { it.text?.toString() == failure }
            capture("confirmation-retry")
            click(await { it.text?.toString() == "Сбросить" })
            assertEquals(2, confirmations)
        }
    }

    @Test fun disclosureChangesStateAndUsesVerticalChevronWhileLinksKeepForwardArrow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val rectangles = mutableMapOf<String, ComposeRect>()
            for (motion in listOf(true, false)) {
                reduced = motion
                show(scenario, "disclosure-$motion") {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Disclosure("Параметры занятия") { Text("Раскрытое содержимое") }
                        EditorialLink("Открыть раздел", "Переход на другой экран", {})
                        Row {
                            DisclosureIndicator(false, Modifier.onGloballyPositioned { rectangles["closed"] = it.boundsInWindow() })
                            DisclosureIndicator(true, Modifier.onGloballyPositioned { rectangles["open"] = it.boundsInWindow() })
                            Icon(AlbionIcons.ArrowForward, null, Modifier.size(20.dp))
                        }
                    }
                }
                val control = target(await { it.text?.toString() == "Параметры занятия" })
                assertEquals("Свёрнуто", control.stateDescription?.toString())
                click(control)
                assertEquals("Развёрнуто", target(await { it.text?.toString() == "Параметры занятия" }).stateDescription?.toString())
                await { it.text?.toString() == "Раскрытое содержимое" }
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                fun span(key: String, from: Float, to: Float): Int {
                    val r = rectangles.getValue(key)
                    val points = mutableListOf<Int>()
                    val background = bitmap.getPixel(r.left.toInt() + 1, r.top.toInt() + 1)
                    for (y in (r.top + r.height * from).toInt()..(r.top + r.height * to).toInt())
                        for (x in r.left.toInt() until r.right.toInt())
                            if (distance(bitmap.getPixel(x, y), background) > 80) points.add(x)
                    assertTrue("Chevron not rendered", points.isNotEmpty())
                    return points.max() - points.min()
                }
                assertTrue("Closed indicator should point down", span("closed", .36f, .44f) > span("closed", .56f, .64f))
                assertTrue("Open indicator should point up", span("open", .36f, .44f) < span("open", .56f, .64f))
                bitmap.recycle()
                capture("disclosure-$motion")
                click(await { it.text?.toString() == "Параметры занятия" })
                assertEquals("Свёрнуто", target(await { it.text?.toString() == "Параметры занятия" }).stateDescription?.toString())
            }
        }
    }

    @Test fun actualConversationDeletionHonoursCancelAndDeletesOnlyTheChosenFixture(): Unit = runBlocking {
        val id = c.conversations.create(Scenario.FREE, "A1")
        val other = c.conversations.create(Scenario.FREE, "A2")
        val title = "UX confirmation fixture $id"
        c.conversations.rename(id, title)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val nav = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))
                show(scenario, "history") { ConversationsScreen(nav) }
                await { it.text?.toString() == "Разговор" }
                val description = "Удалить беседу «$title»"
                click(scrollTo { it.contentDescription?.toString() == description })
                assertWholeText(text("Удалить беседу?"))
                click(await { it.text?.toString() == "Отмена" })
                assertNotNull(c.conversations.conversation(id).first())
                click(scrollTo { it.contentDescription?.toString() == description })
                click(await { it.text?.toString() == "Удалить" })
                repeat(50) { if (c.conversations.conversation(id).first() != null) SystemClock.sleep(50) }
                assertNull(c.conversations.conversation(id).first())
                assertNotNull(c.conversations.conversation(other).first())
            }
        } finally { c.conversations.delete(id); c.conversations.delete(other) }
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
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "selection-and-confirmation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
