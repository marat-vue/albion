package com.marat.albion

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.library.BookTranslationPhase
import com.marat.albion.data.library.BookTranslator
import com.marat.albion.data.library.BookWordSource
import com.marat.albion.data.library.SavedBookWord
import com.marat.albion.ui.library.TranslationSheet
import com.marat.albion.ui.theme.AlbionTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real ML Kit language model, including a new client with all network transports disabled. */
@RunWith(AndroidJUnit4::class)
class BookTranslationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as AlbionApp).container

    @Test fun downloadedModelWorksWithAFreshClientOffline(): Unit = runBlocking {
        installModel()
        assertRussian(withTimeout(15_000) { c.bookTranslator.translate("lighthouse") })
        val wifi = shell("settings get global wifi_on").trim() == "1"
        val mobile = shell("settings get global mobile_data").trim() == "1"
        val connectivity = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val online = connectivity.activeNetwork != null
        try {
            // Wi-Fi and mobile data only: toggling airplane mode crashes radio services on API 37 emulators.
            shell("svc wifi disable")
            shell("svc data disable")
            withTimeout(15_000) { while (connectivity.activeNetwork != null) delay(100) }
            val fresh = BookTranslator(c.appScope)
            val phrase = withTimeout(15_000) { fresh.translate("The book is on the table.") }
            assertRussian(phrase)
            assertTrue("Missing book meaning: $phrase", phrase.contains("книг", ignoreCase = true))
            assertTrue("Missing table meaning: $phrase", phrase.contains("стол", ignoreCase = true))
            assertRussian(withTimeout(15_000) { fresh.translate("a little every day") })
            assertTrue(fresh.state.value.ready)
        } finally {
            shell("svc wifi ${if (wifi) "enable" else "disable"}")
            shell("svc data ${if (mobile) "enable" else "disable"}")
            if (online) withTimeout(30_000) { while (connectivity.activeNetwork == null) delay(200) }
        }
    }

    @Test fun readerSavesBothAutomaticAndExplicitDictionaryMeanings(): Unit = runBlocking {
        c.importer.importIfNeeded()
        installModel()
        for (text in listOf("a little every day", "bank")) {
            val automatic = withTimeout(15_000) { c.bookTranslator.translate(text) }
            val dictionary = if (text == "bank") c.dictionary.lookup(text).flatMap { it.senses }
                .first { it != automatic } else null
            val saved = CompletableDeferred<SavedBookWord>()
            var addition: SavedBookWord? = null
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        CompositionLocalProvider(LocalAppContainer provides c) {
                            AlbionTheme(darkTheme = true) {
                                TranslationSheet(text, isWord = text == "bank", onDismiss = {},
                                    bookSource = BookWordSource("mlkit-test", "A reading test", "Chapter 1"),
                                    bookExample = "A little every day makes a difference.",
                                    onBookWordSaved = { saved.complete(it) })
                            }
                        }
                    } }
                    await { it.text?.toString() == automatic }
                    await { it.contentDescription?.toString() == "Powered by Google Translate" }
                    assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.contains("Groq") == true })
                    if (dictionary != null) {
                        click(await { it.contentDescription?.toString()?.startsWith("Значение ·") == true })
                        click(await { it.text?.toString() == dictionary })
                        await { it.text?.toString() == "Словарь" }
                        await { it.text?.toString() == dictionary }
                    }
                    capture(if (dictionary == null) "phrase-automatic" else "word-dictionary")
                    click(scrollTo { it.text?.toString() == "Сохранить это значение" })
                    val stored = withTimeout(5_000) { saved.await() }
                    addition = stored
                    assertEquals(dictionary ?: automatic, stored.card.meaningRu)
                    assertEquals("A reading test", com.marat.albion.data.library.BookWordsRepository.source(stored.card)!!.title)
                }
            } finally {
                addition?.takeIf { it.added }?.let { assertTrue(c.bookWords.undoAdd(it)) }
            }
        }
    }

    /** Downloads the ML Kit model explicitly, the way the reader's install button does. */
    private suspend fun installModel() = withTimeout(180_000) {
        c.bookTranslator.install(allowMobile = true)
        c.bookTranslator.state.first { it.ready || it.phase == BookTranslationPhase.Failed }
        assertTrue(c.bookTranslator.state.value.message ?: "Model not ready", c.bookTranslator.state.value.ready)
    }

    private fun assertRussian(text: String) = assertTrue("Not a Russian translation: $text", text.any { it in 'А'..'я' })
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(200) {
            visibleNodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing reading action: ${visibleNodes().mapNotNull { it.text }}")
    }
    private fun scrollTo(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(12) {
            visibleNodes().firstOrNull(predicate)?.let { return it }
            visibleNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(300)
        }
        return await(predicate)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("No action target")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureBookTranslation") != "true") return
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "book-translation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
