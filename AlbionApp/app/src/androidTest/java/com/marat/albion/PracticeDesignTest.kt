package com.marat.albion

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.ui.components.MeasureNarrow
import com.marat.albion.ui.games.Game
import com.marat.albion.ui.games.GameHost
import com.marat.albion.data.settings.ThemeMode

import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.primaryRoutes
import com.marat.albion.ui.theme.AlbionTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PracticeDesignTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private val catalogue = listOf(Game.TYPE_ANSWER, Game.IRREGULAR, Game.GRAMMAR_CHOICE, Game.GRAMMAR_GAP,
        Game.SENTENCE, Game.TENSES, Game.PRONUNCIATION, Game.SHADOWING)

    @Test fun everyModeOpensItsSetupAndRoundInThePracticeRoom(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val originalTheme = c.settings.current().themeMode
        try {
            InstrumentationRegistry.getArguments().getString("testDark")?.let {
                c.settings.setThemeMode(if (it == "true") ThemeMode.DARK else ThemeMode.LIGHT)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                activate(await { it.text?.toString() == "Практика" })
                await { it.text?.toString() == "Практика" && it.isHeading }
                capture("practice-home")
                for (game in catalogue) {
                    capture("${game.name}-catalogue")
                    // Фильтр «Грамматика» и упражнение «Грамматика» совпадают по тексту: у карточки есть описание.
                    activate(scrollTo { it.text?.toString() == game.title && hasActionTarget(it) && hasSibling(it, game.tagline) })
                    dismissHelp()
                    await { it.text?.toString() == game.tagline }
                    assertMinimumTarget(await { it.text?.toString() == "Начать" }, "${game.name} start")
                    capture("${game.name}-setup")
                    activate(await { it.text?.toString() == "Начать" })
                    await { it.contentDescription?.toString() == "Закрыть" }
                    SystemClock.sleep(450)
                    capture("${game.name}-round")
                    if (game == Game.IRREGULAR) {
                        activate(await { it.text?.toString() == "Не знаю" })
                        await { it.text?.toString() == "Дальше" }
                        capture("IRREGULAR-answer")
                    }
                    if (game == Game.GRAMMAR_GAP || game == Game.TYPE_ANSWER) {
                        activate(await { it.text?.toString() == "Не знаю" })
                        await { it.text?.toString() == "Дальше" }
                        capture("${game.name}-answer")
                    }
                    activate(await { it.contentDescription?.toString() == "Закрыть" })
                    await { it.text?.toString() == "Начать" }
                    activate(await { it.contentDescription?.toString() == "Назад" })
                    instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                }
            }
        } finally {
            c.settings.setThemeMode(originalTheme)
        }
    }

    @Test fun setupChoicesAndStartRemainUsableWithLargeText(): Unit = runBlocking {
        c.importer.importIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val modes = listOf(Game.TYPE_ANSWER, Game.TENSES, Game.GRAMMAR_GAP, Game.SHADOWING)
            for (game in modes) for (scale in listOf(1.5f, 2f)) {
                val name = "${game.name}-320-$scale"
                val ready = CountDownLatch(1)
                val nav = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(3))
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalAppContainer provides c, LocalDensity provides Density(density, scale)) {
                        key(name) {
                            AlbionTheme { Box(Modifier.width(320.dp).height(740.dp)) { MeasureNarrow { GameHost(game, nav) } } }
                            SideEffect { ready.countDown() }
                        }
                    }
                } }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                dismissHelp()
                val start = await { it.text?.toString() == "Начать" }
                assertMinimumTarget(start, "$name start")
                val bounds = Rect().also(target(start)::getBoundsInScreen)
                val density = instrumentation.targetContext.resources.displayMetrics.density
                assertTrue("Start escaped the viewport ($name)", bounds.bottom <= 741 * density)
                capture("$name-header")
                val count = scrollTo { it.text?.toString() == "10" }
                assertMinimumTarget(count, "$name length")
                if (!target(count).isSelected && !target(count).isChecked) activate(count)
                capture("$name-options")
            }
        }
    }

    @Test fun tenseInputUsesTheNewFieldAndRevealsTheAnswer(): Unit = runBlocking {
        c.importer.importIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use {
            activate(await { it.text?.toString() == "Практика" })
            activate(scrollTo { it.text?.toString() == Game.TENSES.title })
            dismissHelp()
            val input = scrollTo { it.text?.toString() == "Ввод" }
            if (!target(input).isSelected && !target(input).isChecked) activate(input)
            activate(await { it.text?.toString() == "Начать" })
            await { it.contentDescription?.toString() == "Закрыть" }
            await { it.isEditable }
            capture("TENSES-input-round")
            activate(await { it.text?.toString() == "Не знаю" })
            await { it.text?.toString() == "Дальше" }
            capture("TENSES-input-answer")
        }
    }

    private fun assertMinimumTarget(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect().also(target(node)::getBoundsInScreen)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("Narrow touch target: $label ($bounds)", bounds.width() / density >= 48)
        assertTrue("Short touch target: $label ($bounds)", bounds.height() / density >= 48)
    }
    private fun dismissHelp() {
        val node = await { it.text?.toString() in setOf("Понятно", "Начать") }
        if (node.text?.toString() == "Понятно") activate(node)
    }
    private fun hasSibling(node: AccessibilityNodeInfo, text: String): Boolean {
        val parent = node.parent ?: return false
        return (0 until parent.childCount).any { parent.getChild(it)?.text?.toString() == text }
    }
    private fun hasActionTarget(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable || current.isSelected || current.isChecked) return true
            current = current.parent
        }
        return false
    }
    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && !current.isSelected && !current.isChecked) current = current.parent ?: error("Missing action target")
        return current
    }
    private fun activate(node: AccessibilityNodeInfo) {
        assertTrue(target(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(650)
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(35) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, predicate)?.let { node ->
                // Accessibility clips a partly scrolled choice to the visible part of the viewport.
                val visible = Rect().also(target(node)::getBoundsInScreen)
                val density = instrumentation.targetContext.resources.displayMetrics.density
                if (visible.height() / density >= 48f && visible.width() / density >= 48f) return node
            }
            // Narrow test surfaces can be shorter than the emulator window. Start the
            // gesture inside the actual scroller, above the fixed action dock.
            val viewport = find(root) { it.isScrollable && !it.isEditable } ?: root
            val bounds = Rect().also { viewport?.getBoundsInScreen(it) }
            val time = SystemClock.uptimeMillis()
            for (step in 0..15) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(),
                    if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    bounds.exactCenterX(), bounds.top + bounds.height() * (.6f - .25f * step / 15), 0)
                instrumentation.sendPointerSync(event); event.recycle(); SystemClock.sleep(16)
            }
            SystemClock.sleep(100)
            val up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                bounds.exactCenterX(), bounds.top + bounds.height() * .35f, 0)
            instrumentation.sendPointerSync(up); up.recycle()
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        }
        capture("missing-control")
        error("Expected practice control did not appear: ${visibleText(instrumentation.uiAutomation.rootInActiveWindow)}")
    }
    private fun visibleText(node: AccessibilityNodeInfo?): String = if (node == null) "" else
        (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).map { visibleText(node.getChild(it)) }).filter { it.isNotBlank() }.joinToString(" | ")
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected practice screen did not appear")
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePractice") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "practice-room").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
