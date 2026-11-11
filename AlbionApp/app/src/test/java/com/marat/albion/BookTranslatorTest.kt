package com.marat.albion

import com.marat.albion.data.library.BookTranslationBackend
import com.marat.albion.data.library.BookTranslationPhase
import com.marat.albion.data.library.BookTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookTranslatorTest {
    private class Backend(var downloaded: Boolean = true) : BookTranslationBackend {
        var checks = 0
        val downloads = mutableListOf<Boolean>()
        var calls = mutableListOf<String>()
        var download: suspend () -> Unit = {}
        var answer: (String) -> String = { "перевод: $it" }
        override suspend fun isDownloaded(): Boolean { checks++; return downloaded }
        override suspend fun download(allowMobile: Boolean) { downloads += allowMobile; download.invoke(); downloaded = true }
        override suspend fun translate(text: String): String { calls += text; return answer(text) }
    }

    @Test fun installedModelTranslatesAndCachesTheSelectedText(): Unit = runBlocking {
        val backend = Backend()
        val translator = BookTranslator(this, backend)
        assertEquals("перевод: hello", translator.translate(" hello "))
        assertEquals("перевод: a little every day", translator.translate("a little every day"))
        assertEquals("перевод: hello", translator.translate("hello"))
        assertEquals(1, backend.checks)
        assertEquals(listOf("hello", "a little every day"), backend.calls)
        assertTrue(translator.state.value.ready)
        assertTrue(backend.downloads.isEmpty())
    }

    @Test fun missingModelIsNeverDownloadedWithoutTheUser(): Unit = runBlocking {
        val backend = Backend(downloaded = false)
        val translator = BookTranslator(this, backend)
        translator.refresh()
        translator.state.first { it.phase != BookTranslationPhase.Checking }
        assertEquals(BookTranslationPhase.NotInstalled, translator.state.value.phase)
        try { translator.translate("hello"); fail("Translated without a model") }
        catch (_: IllegalStateException) { }
        assertEquals(BookTranslationPhase.NotInstalled, translator.state.value.phase)
        assertTrue(backend.downloads.isEmpty())
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun installWaitsForWifiUnlessMobileDataIsAllowed(): Unit = runBlocking {
        val backend = Backend(downloaded = false)
        val translator = BookTranslator(this, backend)
        translator.install(allowMobile = false)
        translator.state.first { it.ready }
        assertEquals(listOf(false), backend.downloads)
        assertEquals("перевод: hello", translator.translate("hello"))
    }

    @Test fun failedDownloadCanBeRetried(): Unit = runBlocking {
        val backend = Backend(downloaded = false).apply { download = { error("No network") } }
        val translator = BookTranslator(this, backend)
        translator.install(allowMobile = true)
        translator.state.first { it.phase == BookTranslationPhase.Failed }
        assertNotNull(translator.state.value.message)
        backend.download = {}
        translator.install(allowMobile = true)
        translator.state.first { it.ready }
        assertEquals(listOf(true, true), backend.downloads)
    }

    @Test fun translationDuringDownloadDoesNotWaitForIt(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val backend = Backend(downloaded = false).apply { download = { started.complete(Unit); finish.await() } }
        val translator = BookTranslator(this, backend)
        translator.install(allowMobile = false)
        translator.install(allowMobile = false)
        started.await()
        assertEquals(BookTranslationPhase.Downloading, translator.state.value.phase)
        try { translator.translate("hello"); fail("Translated before the model was ready") }
        catch (_: IllegalStateException) { }
        finish.complete(Unit)
        translator.state.first { it.ready }
        assertEquals(1, backend.downloads.size)
    }

    @Test fun cancelledDownloadReturnsToNotInstalled(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val backend = Backend(downloaded = false).apply { download = { started.complete(Unit); CompletableDeferred<Unit>().await() } }
        val translator = BookTranslator(this, backend)
        translator.install(allowMobile = false)
        started.await()
        translator.cancelInstall()
        translator.state.first { it.phase == BookTranslationPhase.NotInstalled }
        backend.download = {}
        translator.install(allowMobile = true)
        translator.state.first { it.ready }
        assertEquals(listOf(false, true), backend.downloads)
    }

    @Test fun invalidSelectionsDoNotCheckTheModel(): Unit = runBlocking {
        val backend = Backend()
        val translator = BookTranslator(this, backend)
        for (text in listOf("  ", "a".repeat(BookTranslator.MAX_SELECTION + 1))) {
            try { translator.translate(text); fail("Invalid selection accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, backend.checks)
    }

    @Test fun emptyOrFailedTranslationsAreNeverCached(): Unit = runBlocking {
        val backend = Backend().apply { answer = { " " } }
        val translator = BookTranslator(this, backend)
        try { translator.translate("hello"); fail("Empty result accepted") }
        catch (_: IllegalStateException) { }
        backend.answer = { error("Translation failed") }
        try { translator.translate("hello"); fail("Failure accepted") }
        catch (_: IllegalStateException) { }
        backend.answer = { "привет" }
        assertEquals("привет", translator.translate("hello"))
        assertEquals(3, backend.calls.size)
        assertEquals(1, backend.checks)
    }
}
