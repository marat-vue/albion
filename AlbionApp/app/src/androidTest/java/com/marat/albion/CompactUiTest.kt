package com.marat.albion

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.library.BookWordSource
import com.marat.albion.ui.library.TranslationSheet
import com.marat.albion.ui.nav.AlbionNavigation
import com.marat.albion.ui.theme.AlbionTheme
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Регрессии: старт без прокрутки, устойчивые подписанные вкладки, перевод раньше действий. */
@RunWith(AndroidJUnit4::class)
class CompactUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as AlbionApp).container
    private val labels = listOf("Сегодня", "Учебник", "Чтение", "Практика", "Прогресс")

    @Test fun compactHomeKeepsStartVisibleAndTabsKeepTheirTargets(): Unit = runBlocking {
        container.ensureStudyToday()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (width in listOf(320, 360)) for (scale in listOf(1f, 1.5f, 2f)) {
                val name = "home-$width-$scale"
                show(scenario, name, width, scale, dark = scale == 2f) { AlbionNavigation() }
                val start = awaitNode { it.text?.toString()?.equals("Начать") == true }
                val startBounds = clickableBounds(start)
                val menu = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Ещё" }
                if (scale == 1f) assertTrue("Обычный шрифт: все пять вкладок должны помещаться ($name)", menu == null)
                val barTop = if (menu != null) clickableBounds(menu).top else clickableBounds(awaitTab("Сегодня")).top
                assertTrue("Начало занятия должно быть над панелью ($name)", startBounds.bottom <= barTop)
                capture(name)
                if (menu != null) {
                    activate(menu)
                    awaitText("Разделы")
                    capture("$name-navigation")
                }
                val before = labels.associateWith { clickableBounds(awaitTab(it)) }
                val density = instrumentation.targetContext.resources.displayMetrics.density
                before.values.forEach { assertTrue("Цель вкладки меньше 48 dp ($name): $it", it.width() / density >= 48f) }

                activate(awaitTab("Прогресс"))
                awaitNode { it.text?.toString() == "Прогресс" && it.isHeading }
                if (menu != null) {
                    activate(awaitText("Ещё"))
                    awaitText("Разделы")
                }
                val after = labels.associateWith { clickableBounds(awaitTab(it)) }
                labels.forEach { label ->
                    assertEquals("Сдвинулась вкладка $label ($name)", before.getValue(label), after.getValue(label))
                }
            }
        }
    }

    @Test fun translationIsVisibleBeforeSavingAndWordEditorStillOpens(): Unit = runBlocking {
        container.importer.importIfNeeded()
        val expected = container.dictionary.lookup("lighthouse").first().senses.first()
        // BottomSheet создаёт отдельное окно: его шрифт берётся из конфигурации Android.
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val name = "translation-$dark-$scale"
                show(scenario, name, 360, scale, dark) {
                    TranslationSheet("lighthouse", isWord = true, onDismiss = {}, onHighlight = {},
                        bookSource = BookWordSource("ui-layout", "Test book", "Chapter one"),
                        bookExample = "The lighthouse stood above the sea.")
                }
                // Значение доступно сразу, без прокрутки мимо сохранения и заметок.
                val meaning = bounds(awaitText(expected))
                assertTrue("Перевод не виден ($name)", meaning.height() > 0)
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "В «Слова из книг»" }?.let {
                    assertTrue("Сохранение оказалось выше перевода ($name)", clickableBounds(it).top >= meaning.bottom)
                }
                capture(name)
                val save = scrollToText("Изменить перед сохранением")
                activate(save)
                awaitText("Слово из книги")
                scrollToText("Пример из книги")
                capture("translation-editor-$dark-$scale")
                val meaningField = awaitNode { it.isEditable && it.text?.toString() == expected }
                activate(meaningField)
                awaitText("Сохранить")
                capture("translation-editor-keyboard-$dark-$scale")
                activate(awaitNode { it.contentDescription?.toString() == "Закрыть: Слово из книги" })
                awaitText("Перевод слова")
            }
        }
    }

    @Test fun additionalDictionaryMeaningsRemainAvailable(): Unit = runBlocking {
        container.importer.importIfNeeded()
        val entries = container.dictionary.lookup("book")
        val additional = entries.flatMap { it.senses }.drop(1).first()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            show(scenario, "translation-details", 360,
                instrumentation.targetContext.resources.configuration.fontScale, false) {
                TranslationSheet("book", isWord = true, onDismiss = {}, onHighlight = {},
                    bookSource = BookWordSource("ui-layout", "Test book", "Chapter one"))
            }
            awaitText(entries.first().senses.first())
            activate(scrollToText("Словарь, автоперевод и ваши карточки"))
            scrollToText(additional)
            capture("translation-details")
        }
    }

    private fun show(scenario: ActivityScenario<MainActivity>, name: String, width: Int,
                     scale: Float, dark: Boolean, content: @Composable () -> Unit) {
        scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalAppContainer provides container,
                    LocalDensity provides Density(density, scale)) {
                    key(name) {
                        AlbionTheme(darkTheme = dark) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                Box(Modifier.width(width.dp).height(640.dp)) { content() }
                            }
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(750)
    }

    private fun awaitText(text: String) = awaitNode { it.text?.toString() == text }

    private fun awaitTab(label: String): AccessibilityNodeInfo {
        awaitText(label)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.text?.toString() == label) matches += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return matches.maxBy { bounds(it).top }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Не найден элемент. Экран: ${visibleText(instrumentation.uiAutomation.rootInActiveWindow)}")
    }

    private fun scrollToText(text: String): AccessibilityNodeInfo {
        repeat(15) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root) { it.text?.toString() == text }?.let { return it }
            // В AlertDialog свайп должен начинаться в прокручиваемом тексте, выше кнопок.
            val viewport = bounds(find(root) { it.isScrollable } ?: root ?: error("Нет окна"))
            val downTime = SystemClock.uptimeMillis()
            for (step in 0..15) {
                val y = viewport.top + viewport.height() * (0.82f - 0.4f * step / 15)
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    viewport.exactCenterX(), y, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
                SystemClock.sleep(16)
            }
            SystemClock.sleep(150)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                viewport.exactCenterX(), viewport.top + viewport.height() * 0.42f, 0)
            instrumentation.sendPointerSync(up)
            up.recycle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(450)
        }
        error("Не найдено после прокрутки: $text")
    }

    private fun activate(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("Нет действия нажатия")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(650)
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun clickableBounds(node: AccessibilityNodeInfo): Rect {
        var target = node
        // Android не помечает уже выбранную вкладку как clickable, но сохраняет её selected-узел.
        while (!target.isClickable && !target.isSelected) target = target.parent ?: error("Нет области нажатия")
        return bounds(target)
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }

    private fun visibleText(node: AccessibilityNodeInfo?): String = buildString {
        if (node == null) return@buildString
        if (node.isVisibleToUser && node.text != null) append(node.text).append(" | ")
        for (i in 0 until node.childCount) append(visibleText(node.getChild(i)))
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureCompact") != "true") return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "compact-ui").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Нет снимка экрана")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
