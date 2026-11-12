package com.marat.albion

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
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
import com.marat.albion.ui.grammar.GrammarScreen
import com.marat.albion.ui.grammar.grammarStages
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
class GrammarRoadmapTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private val levels = listOf("A1", "A2", "B1", "B2", "C1")

    @Test fun stagesOpenLessonsSearchAcrossLevelsAndSurviveRecreation(): Unit = runBlocking {
        c.importer.importIfNeeded()
        val stages = grammarStages(c.grammar.book())
        assertEquals(levels, stages.map { it.level })
        assertEquals(c.grammar.book().lessons.map { it.id }.toSet(), stages.flatMap { it.topics }.map { it.id }.toSet())
        val target = stages.last().topics.first()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            activate(await { it.text?.toString() == "Учебник" })
            levels.forEach { await { node -> node.text?.toString() == it } }
            capture("grammar-atlas")
            activate(await { it.text?.toString() == "C1" })
            scrollTo { it.text?.toString() == stages.last().title }
            capture("grammar-c1")
            activate(scrollTo { it.text?.toString()?.contains(target.title) == true })
            await { it.contentDescription?.toString() == "Размер текста" }
            capture("grammar-lesson")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { it.isEditable }
            scenario.recreate()
            await { it.isEditable }
            val active = await { it.text?.toString() == "C1" }
            var selected: AccessibilityNodeInfo? = active
            while (selected != null && !selected.isSelected) selected = selected.parent
            assertNotNull("Selected stage was lost after recreation", selected)
            val first = stages.first().topics.first()
            setSearch(first.title)
            activate(await { !it.isEditable && it.text?.toString()?.contains(first.title) == true })
            await { it.contentDescription?.toString() == "Размер текста" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { it.isEditable && it.text?.toString() == first.title }
        }
    }

    @Test fun compactAtlasKeepsEveryStageAccessibleWithLargeText(): Unit = runBlocking {
        c.importer.importIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (width in listOf(320, 360)) for (scale in listOf(1f, 1.5f, 2f)) {
                val name = "grammar-$width-$scale"
                val ready = CountDownLatch(1)
                val nav = Navigator(primaryRoutes.associateWith { NavBackStack<NavKey>(it) }, mutableIntStateOf(1))
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalAppContainer provides c, LocalDensity provides Density(density, scale)) {
                        key(name) {
                            AlbionTheme { Box(Modifier.width(width.dp).height(740.dp)) { MeasureNarrow { GrammarScreen(nav) } } }
                            SideEffect { ready.countDown() }
                        }
                    }
                } }
                assertTrue("Composition did not become ready ($name)", ready.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
                capture("$name-atlas")
                levels.forEach { level ->
                    reveal(level)
                    val node = await { it.text?.toString() == level }
                    var target = node
                    // Android exposes the active Tab as selected, without a redundant click action.
                    while (!target.isClickable && !target.isSelected) target = target.parent ?: error("Missing stage target ($name)")
                    val bounds = Rect().also(target::getBoundsInScreen)
                    val density = instrumentation.targetContext.resources.displayMetrics.density
                    assertTrue("Stage $level has a narrow target ($name)", bounds.width() / density >= 48)
                    assertTrue("Stage $level has a short target ($name)", bounds.height() / density >= 48)
                }
                reveal("B2")
                activate(await { it.text?.toString() == "B2" })
                await { node -> node.isSelected && find(node) { it.text?.toString() == "B2" } != null }
                capture("$name-selected")
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable && !it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                instrumentation.waitForIdleSync()
                capture(name)
            }
        }
    }

    private fun setSearch(value: String) {
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(await { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
        instrumentation.waitForIdleSync()
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("Missing clickable parent")
        return target
    }
    private fun activate(node: AccessibilityNodeInfo) {
        assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(35) {
            val root = freshRoot()
            find(root, predicate)?.let { return it }
            val list = find(root) { it.isScrollable && !it.isEditable } ?: error("Missing scrollable list")
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        }
        error("Expected roadmap topic did not appear")
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find(freshRoot(), predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected grammar content did not appear")
    }
    /** Levels share a horizontally scrolling row; with large text the last ones start off screen. */
    private fun reveal(text: String) {
        repeat(6) {
            val root = freshRoot()
            if (find(root) { it.text?.toString() == text } != null) return
            // Off-screen tiles leave the tree, so scroll the row that holds any of the levels.
            var row = findAny(root) { it.text?.toString() in levels } ?: return
            while (!row.isScrollable) row = row.parent ?: return
            row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        }
    }
    private fun findAny(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) findAny(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureEditorial") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "grammar-roadmap").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    /** Начиная с Android 13 узлы кэшируются: после анимаций и пересоздания экрана читаем дерево заново. */
    private fun freshRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

}
