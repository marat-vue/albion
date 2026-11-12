package com.marat.albion

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.settings.ThemeMode
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full user journey in both editorial themes (milk and charcoal), including study and reduced motion. */
@RunWith(AndroidJUnit4::class)
class EditorialDesignTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container

    @Test fun primaryScreensAndSensoryPreferencesWorkInBothThemes(): Unit = runBlocking {
        c.ensureStudyToday()
        val original = c.settings.current()
        val manager = instrumentation.targetContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val originalNight = manager.nightMode
        try {
            for (night in listOf(false, true)) {
                c.settings.setThemeMode(if (night) ThemeMode.DARK else ThemeMode.LIGHT)
                shell("cmd uimode night ${if (night) "yes" else "no"}")
                val edition = if (night) "editorial-dark" else "editorial-light"
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    await { it.text?.toString() == "Начать" || it.text?.toString() == "Открыть библиотеку" }
                    assertThemeBackground(night)
                    capture("home-$edition")
                    click { it.text?.toString() == "Учебник" && it.isVisibleToUser }
                    await { it.isEditable }
                    capture("grammar-$edition")
                    click { it.text?.toString() in setOf("Читать урок", "Сначала открыть правило") }
                    await { it.contentDescription?.toString() == "Размер текста" }
                    capture("lesson-$edition")
                    click { it.contentDescription?.toString() == "Назад" }
                    await { it.isEditable }
                    click { it.text?.toString() == "Чтение" }
                    dismissHelp()
                    await { it.text?.toString() == "Библиотека" }
                    capture("library-$edition")
                    click { it.text?.toString() == "The Keeper of Gull Island" }
                    await { it.contentDescription?.toString() == "Оглавление" }
                    capture("reader-$edition")
                    click { it.contentDescription?.toString() == "Оглавление" }
                    click { it.text?.toString() == "Chapter 6. The Light" }
                    await { it.text?.toString() == "The Light" }
                    capture("chapter-opening-$edition")
                    click { it.contentDescription?.toString() == "Меню чтения" }
                    await { it.text?.toString() == "Выделения и заметки" }
                    capture("reader-menu-$edition")
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    click { it.contentDescription?.toString() == "Назад" }
                    await { it.text?.toString() == "Библиотека" }
                    click { it.text?.toString() == "Практика" }
                    scrollTo { it.text?.toString() == "Словарный запас" }
                    capture("practice-$edition")
                    click { it.text?.toString() == "Прогресс" }
                    await { it.text?.toString() == "Прогресс" && it.isHeading }
                    capture("progress-$edition")
                    click { it.contentDescription?.toString() == "Настройки" }
                    await { it.text?.toString() == "Звук и доступность" }
                    capture("settings-$edition")
                    click { it.contentDescription?.toString() == "Назад" }
                    await { it.text?.toString() == "Прогресс" && it.isHeading }
                    click { it.text?.toString() == "Сегодня" }
                    click { it.text?.toString() == "Начать" }
                    await { it.text?.toString() == "Показать ответ" }
                    capture("study-$edition")
                    click { it.text?.toString() == "Показать ответ" }
                    await { it.text?.toString() == "Хорошо" }
                    capture("answer-$edition")
                    click { it.contentDescription?.toString() == "Закрыть" }
                }
            }
            c.settings.setInterfaceSounds(false)
            c.settings.setHapticsEnabled(false)
            c.settings.setReduceMotion(true)
            val saved = c.settings.current()
            assertTrue(!saved.interfaceSounds && !saved.hapticsEnabled && saved.reduceMotion)
            ActivityScenario.launch(MainActivity::class.java).use {
                click { it.text?.toString() == "Начать" }
                click { it.text?.toString() == "Показать ответ" }
                await { it.text?.toString() == "Хорошо" }
                capture("study-reduced-motion")
            }
        } finally {
            shell("cmd uimode night ${when (originalNight) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_AUTO -> "auto"; else -> "no" }}")
            c.settings.setInterfaceSounds(original.interfaceSounds)
            c.settings.setHapticsEnabled(original.hapticsEnabled)
            c.settings.setReduceMotion(original.reduceMotion)
            c.settings.setThemeMode(original.themeMode)
        }
    }

    private fun assertThemeBackground(dark: Boolean) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        try {
            val background = Color.red(bitmap.getPixel(2, bitmap.height / 2))
            assertTrue("Theme background did not apply: $background", if (dark) background < 30 else background > 220)
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        SystemClock.sleep(350)
    }

    private fun dismissHelp() {
        SystemClock.sleep(400)
        find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Понятно" }?.let(::activate)
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) = activate(await(predicate))
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(15) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, predicate)?.let { return it }
            find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
        }
        error("Expected UI element is not reachable by scrolling")
    }
    private fun activate(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("Missing clickable parent")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) {
            find(freshRoot(), predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected UI element did not appear")
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureEditorial") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(450)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "editorial").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    /** Начиная с Android 13 узлы кэшируются: после анимаций и пересоздания экрана читаем дерево заново. */
    private fun freshRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

}
