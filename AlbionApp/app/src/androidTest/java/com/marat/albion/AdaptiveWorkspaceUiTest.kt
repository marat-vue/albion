package com.marat.albion

import android.graphics.Bitmap
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.ui.browse.BrowseScreen
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.conversation.SpeechModelControls
import com.marat.albion.ui.conversation.AiControls
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.study.StudyScreen
import com.marat.albion.ui.theme.AlbionTheme
import com.marat.albion.ui.theme.LocalReducedMotion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run on the real window: 320×740 dp / 200% and 740×320 dp / 100%, including the real IME. */
@RunWith(AndroidJUnit4::class)
class AdaptiveWorkspaceUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private val density get() = instrumentation.targetContext.resources.displayMetrics.density
    private fun navigator() = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(0))

    @Before fun configureRealWindow() {
        // Set the logical display size with adb wm size; IME and ActivityScenario retain this rotation.
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
    }

    @Test fun studyKeepsTheWordReadableAndEveryRatingReachable(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val queue = c.study.buildQueue(Deck.WORDS)
        val card = (queue.learning + queue.reviews + queue.news).first()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "study") { StudyScreen(com.marat.albion.data.repo.StudySource.Dictionary(Deck.WORDS), navigator(), answerLimit = 10) }
            await { it.text?.toString() == "Показать ответ" }
            val word = await { it.text?.toString() == card.term && bounds(it).height() >= 20 * density }
            assertTrue("The word is squeezed away", bounds(word).width() > 12 * density)
            capture("study-front")
            activate(await { it.text?.toString() == "Показать ответ" })
            for (label in listOf("Снова", "Трудно", "Хорошо", "Легко")) {
                val rating = scrollTo { it.text?.toString() == label && usable(it) }
                assertTrue("Rating is too small: $label", bounds(target(rating)).height() >= 48 * density)
                await { it.text?.toString() == card.term && bounds(it).height() >= 20 * density }
            }
            capture("study-answer")
        }
    }

    @Test fun modelPanelsKeepTheirCloseAndInstallationActionsReachable(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (speech in listOf(true, false)) {
                val dismissed = mutableStateOf(false)
                show(scenario, "model-$speech") {
                    ScrollableSheet(if (speech) "Голосовой ввод" else "Собеседник · Groq", { dismissed.value = true }) {
                        if (speech) SpeechModelControls() else AiControls()
                    }
                }
                val label = if (speech) "Голосовой ввод" else "Собеседник · Groq"
                await { it.contentDescription?.toString() == "Закрыть: $label" }
                capture(if (speech) "whisper-panel-top" else "groq-panel-top")
                val action = scrollTo { it.text?.toString() in setOf("Загрузить модель", "Повторить загрузку", "Удалить выбранную модель",
                    "Получить ключ на console.groq.com", "Проверить связь") && usable(it) }
                assertTrue(bounds(target(action)).height() >= 48 * density)
                capture(if (speech) "whisper-panel" else "groq-panel")
                val close = await { it.contentDescription?.toString() == "Закрыть: $label" }
                tap(close)
                instrumentation.waitForIdleSync()
                assertTrue("The pinned close action must respond to a touch after scrolling", dismissed.value)
            }
        }
    }

    @Test fun searchKeepsResultsAboveTheKeyboardAndPreservesFilters(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        configureRealWindow()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "browse") { BrowseScreen(Deck.WORDS, navigator()) }
            val field = await { it.isEditable }
            tap(field)
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "about")
            }))
            await { it.text?.toString() == "Фильтры" }
            val until = SystemClock.uptimeMillis() + 5000
            while (automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            val ime = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: run { capture("missing-keyboard"); error("The regression must be checked with a visible keyboard") }
            val keyboard = Rect().also(ime::getBoundsInScreen)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            capture("browse-keyboard")
            val word = await { !it.isEditable && it.text?.toString() == "about" }
            assertTrue("Search result is behind the keyboard", bounds(word).bottom <= keyboard.top)
            activate(await { it.text?.toString() == "Фильтры" })
            activate(await { it.contentDescription?.toString() == "Статус карточек" })
            activate(scrollTo { it.text?.toString() == "Пора повторить" })
            activate(scrollTo { it.text?.toString() == "Показать карточки" && usable(it) })
            val filters = await { it.text?.toString() == "Фильтры · 1" || it.contentDescription?.toString() == "Статус карточек" }
            if (filters.text?.toString() == "Фильтры · 1") activate(filters)
            await { it.text?.toString() == "Пора повторить" }
            capture("browse-saved-filter")
        }
    }

    private fun show(scenario: ActivityScenario<MainActivity>, name: String, content: @Composable () -> Unit) {
        scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalAppContainer provides c, LocalReducedMotion provides true) {
                key(name) {
                    AlbionTheme {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
        } }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        InstrumentationRegistry.getArguments().getString("expectedWidthDp")?.toInt()?.let { expected ->
            val screen = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            assertTrue("Wrong test window: ${screen.width} px at density $density", screen.width / density == expected.toFloat())
            screen.recycle()
        }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n = node
        while (!n.isClickable) n = n.parent ?: error("Missing click target: ${node.text}")
        return n
    }
    private fun usable(node: AccessibilityNodeInfo): Boolean {
        val b = bounds(target(node))
        return b.width() >= 48 * density && b.height() >= 48 * density
    }
    private fun activate(node: AccessibilityNodeInfo) {
        assertTrue(target(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val b = bounds(node)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, b.exactCenterX(), b.exactCenterY(), 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        SystemClock.sleep(500)
    }
    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    private fun fresh(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return all(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) {
            fresh().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI element: ${fresh().mapNotNull { node -> (node.text ?: node.contentDescription)?.let { "$it ${bounds(node)} clickable=${node.isClickable}" } }.joinToString(" | ")}")
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(15) {
            val nodes = fresh()
            nodes.firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            val scrollers = nodes.filter { it.isVisibleToUser && it.isScrollable }.asReversed()
            if (!scrollers.any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }) {
                capture("unreachable-action")
                error("No scrollable area can reveal the action: ${nodes.mapNotNull { it.text?.toString() }.joinToString(" | ")}")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(800)
        }
        error("Action is not reachable by scrolling")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAdaptive") != "true") return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-workspace").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
