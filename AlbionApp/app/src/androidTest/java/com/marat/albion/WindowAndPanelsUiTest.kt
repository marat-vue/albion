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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.grammar.*
import com.marat.albion.ui.components.*
import com.marat.albion.ui.nav.*
import com.marat.albion.ui.settings.SettingsScreen
import com.marat.albion.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WindowAndPanelsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private var activity: MainActivity? = null
    private var reduced by mutableStateOf(true)
    private var folds by mutableStateOf<List<WindowFold>>(emptyList())
    private val density get() = activity!!.resources.displayMetrics.density

    @Before fun prepare(): Unit = runBlocking {
        val landscape = InstrumentationRegistry.getArguments().getString("testLandscape") == "true"
        instrumentation.uiAutomation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        c.ensureStudyToday()
    }

    @Test fun settingsDisclosureLabelsKeepWholeWordsAtTheSystemFontSize() {
        val labels = listOf(
            "Карточки и озвучка" to "Перевод, транскрипция и воспроизведение",
            "Чтение и учебник" to "Размер текста, интервал и поля",
            "Звук и доступность" to "Тактильный отклик и движение",
            "Расширенные настройки повторения" to "Память, начало дня и персональная подстройка",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "disclosure-settings") {
                NavigationWindow(SettingsRoute, false) { _, _ -> SettingsScreen({}) }
            }
            for ((index, pair) in labels.withIndex()) {
                val node = reveal { it.text?.toString() == pair.first }
                assertWholeText(text(pair.first))
                assertWholeText(text(pair.second))
                assertEquals(16.sp, text(pair.first).layout.layoutInput.style.fontSize)
                assertEquals(13.sp, text(pair.second).layout.layoutInput.style.fontSize)
                assertEquals("Свёрнуто", target(node).stateDescription?.toString())
                click(node)
                assertEquals("Развёрнуто", target(await { it.text?.toString() == pair.first }).stateDescription?.toString())
                reveal { it.text?.toString() == pair.first }
                capture("disclosure-$index")
                assertSafeTargets()
                click(await { it.text?.toString() == pair.first })
                assertEquals("Свёрнуто", target(await { it.text?.toString() == pair.first }).stateDescription?.toString())
            }
        }
    }

    @Test fun disclosureReflowPreservesItsExpandedContentAndState() {
        var width by mutableStateOf(220.dp)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "disclosure-reflow") {
                NavigationWindow(SettingsRoute, false) { _, _ ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Disclosure("Расширенные настройки повторения", "Память, начало дня и персональная подстройка",
                            Modifier.width(width), icon = AlbionIcons.Route) { Text("Содержимое остаётся открытым") }
                    }
                }
            }
            click(await { it.text?.toString() == "Расширенные настройки повторения" })
            for (next in listOf(760.dp, 220.dp)) {
                instrumentation.runOnMainSync { width = next }
                SystemClock.sleep(500)
                assertWholeText(text("Расширенные настройки повторения"))
                assertWholeText(text("Память, начало дня и персональная подстройка"))
                assertEquals("Развёрнуто", target(await { it.text?.toString() == "Расширенные настройки повторения" }).stateDescription?.toString())
                await { it.text?.toString() == "Содержимое остаётся открытым" }
            }
            capture("disclosure-reflow")
        }
    }

    @Test fun tabletOpensAnOverviewAndKeepsTheExplicitLessonSelection(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "tablet-overview") { AlbionNavigation() }
            selectDestination("Учебник")
            val overview = await { it.text?.toString() == "Обзор учебника" }
            assertTrue("Overview is not in the right pane", bounds(overview).left > activity!!.resources.displayMetrics.widthPixels * .35f)
            assertFalse("Recommendation opened a lesson automatically", fresh().any { it.contentDescription?.toString() == "Размер текста" })
            val captions = semantics().count { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Следующий шаг ·") } == true }
            assertEquals("Recommendation is duplicated in the list", 1, captions)
            capture("tablet-overview")
            val topic = anotherLesson()
            click(reveal { it.text?.toString() == topic.title })
            assertLesson(topic)
            assertFalse(fresh().any { it.isVisibleToUser && it.text?.toString() == "Обзор учебника" })
            selectDestination("Чтение")
            selectDestination("Учебник")
            assertLesson(topic)
            capture("tablet-selected-lesson")
            click(await { it.contentDescription?.toString() == "Назад" })
            await { it.text?.toString() == "Обзор учебника" }
            assertSafeTargets()
        }
    }

    @Test fun verticalHingeSeparatesPanesAndFocusUsesOneUnbrokenPanel(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(size.widthPixels / 2 - gap, 0, size.widthPixels / 2 + gap, size.heightPixels), FoldAxis.Vertical))
            show(scenario, "vertical-fold") { CompositionLocalProvider(LocalWindowFolds provides folds) { AlbionNavigation() } }
            selectDestination("Учебник")
            val overview = await { it.text?.toString() == "Обзор учебника" }
            assertTrue(bounds(overview).left >= folds.single().bounds.right)
            assertNoTargetsOnFold()
            capture("vertical-fold-overview")
            val topic = anotherLesson()
            click(reveal { it.text?.toString() == topic.title })
            assertLesson(topic)
            instrumentation.runOnMainSync {
                folds = listOf(WindowFold(IntRect(size.widthPixels * 3 / 5 - gap, 0, size.widthPixels * 3 / 5 + gap, size.heightPixels), FoldAxis.Vertical))
            }
            SystemClock.sleep(700)
            assertLesson(topic)
            assertNoTargetsOnFold()
            click(await { it.contentDescription?.toString() == "Настройки" })
            await { it.text?.toString() == "Настройки" }
            assertNoTargetsOnFold()
            assertSafeTargets()
            capture("vertical-fold-focused-settings")
            click(await { it.contentDescription?.toString() == "Назад" })
            assertLesson(topic)
        }
    }

    @Test fun tabletopKeepsNavigationAboveTheFoldAndDetailsBelowIt(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(0, size.heightPixels / 2 - gap, size.widthPixels, size.heightPixels / 2 + gap), FoldAxis.Horizontal))
            show(scenario, "tabletop-fold") { CompositionLocalProvider(LocalWindowFolds provides folds) { AlbionNavigation() } }
            selectDestination("Учебник")
            val overview = await { it.text?.toString() == "Обзор учебника" }
            assertTrue("Overview crosses the horizontal hinge", bounds(overview).top >= folds.single().bounds.bottom)
            assertNoTargetsOnFold()
            val tab = target(await { it.text?.toString() == "Сегодня" && actionOrNull(it) != null })
            assertTrue("Navigation crosses the horizontal hinge", bounds(tab).bottom <= folds.single().bounds.top)
            capture("tabletop-overview")
            val topic = anotherLesson()
            click(reveal { it.text?.toString() == topic.title })
            assertLesson(topic)
            assertNoTargetsOnFold()
            instrumentation.runOnMainSync { folds = emptyList() }
            SystemClock.sleep(700)
            assertLesson(topic)
            assertSafeTargets()
            capture("unfolded-selected-lesson")
        }
    }

    @Test fun aLessonOpenedOutsideTheContentsUsesOnePhysicalPanel(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(size.widthPixels / 2 - gap, 0, size.widthPixels / 2 + gap, size.heightPixels), FoldAxis.Vertical))
            val topic = c.grammar.book().lessons.first()
            show(scenario, "direct-lesson") {
                CompositionLocalProvider(LocalWindowFolds provides folds) {
                    val stacks = primaryRoutes.associateWith { route -> key(route) { androidx.navigation3.runtime.rememberNavBackStack(route) } }
                    val selected = remember { mutableIntStateOf(0) }
                    val navigator = remember(stacks, selected) { Navigator(stacks, selected) }
                    NavigationWindow(TopicRoute(topic.id), false) { split, _ ->
                        check(!split)
                        com.marat.albion.ui.grammar.TopicScreen(topic.id, navigator)
                    }
                }
            }
            assertLesson(topic)
            assertNoTargetsOnFold()
            capture("direct-lesson-single-panel")
        }
    }

    @Test fun foldedMenusDialogsAndSheetsStayInsideTheirOwnerPanel() {
        var menu by mutableStateOf(false)
        var deleting by mutableStateOf(false)
        var sheet by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(size.widthPixels / 2 - gap, 0, size.widthPixels / 2 + gap, size.heightPixels), FoldAxis.Vertical))
            show(scenario, "folded-popups") {
                CompositionLocalProvider(LocalWindowFolds provides folds) {
                    NavigationWindow(SettingsRoute, false) { _, _ ->
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            Box(Modifier.align(androidx.compose.ui.Alignment.End)) {
                                androidx.compose.material3.TextButton({ menu = true }) { Text("Показать меню") }
                                PaneDropdownMenu(menu, { menu = false }) {
                                    androidx.compose.material3.DropdownMenuItem({ Text("Параметры") }, { menu = false })
                                }
                            }
                            androidx.compose.material3.TextButton({ deleting = true }) { Text("Показать подтверждение") }
                            androidx.compose.material3.TextButton({ sheet = true }) { Text("Показать панель") }
                        }
                        if (deleting) DestructiveConfirmationDialog("Удалить тестовый элемент?", "Проверка положения подтверждения.",
                            { deleting = false }, { deleting = false })
                        if (sheet) ScrollableSheet("Параметры панели", { sheet = false }) {
                            Text("Содержимое панели")
                            androidx.compose.material3.TextButton({ sheet = false }) { Text("Готово") }
                        }
                    }
                }
            }
            for (fraction in listOf(.5f, .4f)) {
                val center = (size.widthPixels * fraction).toInt()
                instrumentation.runOnMainSync {
                    folds = listOf(WindowFold(IntRect(center - gap, 0, center + gap, size.heightPixels), FoldAxis.Vertical))
                }
                SystemClock.sleep(400)
                val suffix = if (fraction == .5f) "" else "-right"
                click(await { it.text?.toString() == "Показать меню" })
                await { it.text?.toString() == "Параметры" }
                SystemClock.sleep(500)
                assertNoTargetsOnFold()
                capture("folded-anchored-menu$suffix")
                click(await { it.text?.toString() == "Параметры" })
                click(await { it.text?.toString() == "Показать подтверждение" })
                await { it.text?.toString() == "Удалить" }
                SystemClock.sleep(500)
                assertNoTargetsOnFold()
                capture("folded-confirmation$suffix")
                click(await { it.text?.toString() == "Отмена" })
                click(await { it.text?.toString() == "Показать панель" })
                await { it.text?.toString() == "Готово" }
                SystemClock.sleep(500)
                assertNoTargetsOnFold()
                capture("folded-bottom-sheet$suffix")
                click(await { it.text?.toString() == "Готово" })
            }
        }
    }

    @Test fun rtlNavigationKeepsTheRailAndPanelsOutsideTheHinge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(size.widthPixels / 2 - gap, 0, size.widthPixels / 2 + gap, size.heightPixels), FoldAxis.Vertical))
            show(scenario, "rtl-fold") {
                CompositionLocalProvider(LocalWindowFolds provides folds,
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                    AlbionNavigation()
                }
            }
            // A focused root uses one physical region before switching to the two-pane contents.
            assertNoTargetsOnFold()
            selectDestination("Учебник")
            await { it.text?.toString() == "Обзор учебника" }
            assertNoTargetsOnFold()
            val tab = target(await { it.text?.toString() == "Сегодня" && actionOrNull(it) != null })
            assertTrue("RTL rail must stay on the right physical panel", bounds(tab).left >= folds.single().bounds.right)
            capture("rtl-fold-overview")
        }
    }

    @Test fun largeTextOnAFoldUsesASingleReadablePanel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            val size = activity!!.resources.displayMetrics
            val gap = (12 * density).toInt()
            folds = listOf(WindowFold(IntRect(size.widthPixels / 2 - gap, 0, size.widthPixels / 2 + gap, size.heightPixels), FoldAxis.Vertical))
            show(scenario, "large-fold") { CompositionLocalProvider(LocalWindowFolds provides folds) { AlbionNavigation() } }
            selectDestination("Учебник")
            SystemClock.sleep(400)
            assertFalse(fresh().any { it.text?.toString() == "Обзор учебника" })
            assertNoTargetsOnFold()
            assertSafeTargets()
            capture("large-text-single-fold-panel")
        }
    }

    @Test fun primarySectionsAndSettingsKeepTargetsInsideRealSystemInsets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "safe-sections") { AlbionNavigation() }
            if (InstrumentationRegistry.getArguments().getString("testLandscape") == "true") {
                val cutout = activity!!.window.decorView.rootWindowInsets.displayCutout
                assertTrue("A real side cutout is required for this configuration", cutout != null && (cutout.safeInsetLeft > 0 || cutout.safeInsetRight > 0))
            }
            for (label in listOf("Сегодня", "Учебник", "Чтение", "Практика", "Прогресс")) {
                selectDestination(label)
                SystemClock.sleep(400)
                assertSafeTargets()
            }
            capture("safe-primary-sections")
            click(await { it.contentDescription?.toString() == "Настройки" })
            await { it.text?.toString() == "Настройки" }
            assertSafeTargets()
            capture("safe-focused-settings")
        }
    }

    private suspend fun anotherLesson(): Topic {
        val book = c.grammar.book()
        val progress = c.grammar.observeProgress().first().associateBy { it.topicId }
        val next = GrammarNextStep.recommend(book, progress, c.grammar.observeQuizTopics().first().toSet(), System.currentTimeMillis(), lastId = c.settings.current().lastGrammarTopic)
        return book.learningOrder.first { it.kind == TopicKind.Lesson && it.level == next?.topic?.level && it.id != next?.topic?.id }
    }
    private fun assertLesson(topic: Topic): Unit = runBlocking {
        await { it.contentDescription?.toString() == "Размер текста" }
        repeat(50) { if (c.settings.current().lastGrammarTopic != topic.id) SystemClock.sleep(50) }
        assertEquals("An overview replaced the user's explicit choice", topic.id, c.settings.current().lastGrammarTopic)
    }
    private fun reveal(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val n = scrollTo(predicate)
        val action = actionOrNull(n) ?: n
        action.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(300)
        return await(predicate)
    }
    private fun actionOrNull(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable && !current.isSelected) current = current.parent
        return current
    }
    private fun fresh(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
    }
    private fun selectDestination(label: String) {
        val direct = fresh().firstOrNull { it.isVisibleToUser && it.text?.toString() == label && actionOrNull(it) != null }
        if (direct != null) { if (actionOrNull(direct)?.isSelected != true) click(direct) } else {
            click(await { it.text?.toString() == "Ещё" })
            if (folds.isNotEmpty()) assertNoTargetsOnFold()
            val tab = await { it.text?.toString() == label && actionOrNull(it) != null }
            if (actionOrNull(tab)?.isSelected == true) click(await { it.contentDescription?.toString() == "Закрыть разделы" }) else click(tab)
        }
    }
    private fun assertSafeTargets() {
        val inset = activity!!.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
        val size = activity!!.resources.displayMetrics
        val safe = android.graphics.Rect(inset.left, inset.top, size.widthPixels - inset.right, size.heightPixels - inset.bottom)
        val targets = fresh().filter { it.isVisibleToUser && (it.isClickable || it.isCheckable || it.isSelected) && !bounds(it).isEmpty }
        assertTrue("No interactive targets were inspected", targets.isNotEmpty())
        for (node in targets) {
            val r = visibleTargetBounds(node)
            if (r.isEmpty) continue
            assertTrue("Target overlaps system UI: ${node.text}/${node.contentDescription}: $r, safe=$safe",
                r.left >= safe.left - 1 && r.top >= safe.top - 1 && r.right <= safe.right + 1 && r.bottom <= safe.bottom + 1)
        }
    }
    // Accessibility touch bounds include the full 48 dp expansion even for a row clipped by a scroll viewport.
    // The scroll ancestor determines the visible, tappable portion of such rows.
    private fun visibleTargetBounds(node: AccessibilityNodeInfo): android.graphics.Rect {
        val result = bounds(node)
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable && !result.intersect(bounds(parent))) result.setEmpty()
            parent = parent.parent
        }
        return result
    }
    private fun assertNoTargetsOnFold() {
        assertSafeTargets()
        for (node in fresh().filter { it.isVisibleToUser && (it.isClickable || it.isCheckable || it.isSelected) }) {
            val r = visibleTargetBounds(node)
            for (fold in folds) {
                val h = fold.bounds
                assertFalse("Interactive target crosses a hinge: ${node.text}/${node.contentDescription}: $r; $h",
                    android.graphics.Rect.intersects(r, android.graphics.Rect(h.left, h.top, h.right, h.bottom)))
            }
        }
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
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "window-and-panels").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
