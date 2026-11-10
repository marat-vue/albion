package com.marat.albion

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.ReviewLogEntity
import com.marat.albion.data.fsrs.Fsrs
import com.marat.albion.data.fsrs.FsrsOptimizer
import com.marat.albion.data.fsrs.OptimizeOutcome
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.fsrs.ReviewEvent
import com.marat.albion.data.repo.StudyRepository
import com.marat.albion.data.settings.SettingsRepository
import com.marat.albion.ui.study.StudyState
import com.marat.albion.ui.study.StudyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Проверки на настоящем SQLite: сбой между двумя записями имитируется триггером. */
@RunWith(AndroidJUnit4::class)
class StudyPersistenceTest {
    private lateinit var db: AlbionDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: StudyRepository
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()
    private val now = 1_700_000_000_000L
    private val card = CardEntity(
        id = "words:test#0", deck = Deck.WORDS.id, position = 0, term = "test", ipa = "/test/",
        pos = "noun", level = "A1", meaningEn = "test", meaningRu = "проверка",
        example1 = "A test.", example2 = "", audio = null, extra = null,
    )

    @Before
    fun setUp() = runBlocking {
        val context = instrumentation.targetContext
        db = Room.inMemoryDatabaseBuilder(context, AlbionDatabase::class.java).build()
        settings = SettingsRepository(context)
        settings.setFsrsParams(null, 0, 0f)
        repo = StudyRepository(db, settings, Json)
        db.cards().upsert(listOf(card))
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { store.clear() }
        runBlocking { settings.setFsrsParams(null, 0, 0f) }
        db.close()
    }

    private fun failLog(operation: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_log BEFORE $operation ON review_log BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
    }

    private fun allowLog() = db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_log")

    private suspend fun expectSqlFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Ожидалась искусственная ошибка SQLite")
        } catch (_: SQLiteException) {
            // Откат проверяется чтением обеих таблиц после исключения.
        }
    }

    @Test
    fun failedFirstAnswerLeavesNeitherStateNorHistory() = runBlocking {
        failLog("INSERT")
        expectSqlFailure { repo.answer(card, Rating.Good, 100, now) }
        assertNull(db.reviews().state(card.id))
        assertEquals(0, db.reviews().countAllSince(0))
        allowLog()
        val (id, memory) = repo.answer(card, Rating.Good, 100, now)
        assertEquals(1, memory.reps)
        assertNotNull(db.reviews().log(id))
    }

    @Test
    fun failedSubsequentAnswerPreservesPreviousStateAndHistory() = runBlocking {
        val (firstId, _) = repo.answer(card, Rating.Easy, 100, now)
        val before = db.reviews().state(card.id)
        failLog("INSERT")
        expectSqlFailure { repo.answer(card, Rating.Again, 200, now + Fsrs.DAY) }
        assertEquals(before, db.reviews().state(card.id))
        assertNotNull(db.reviews().log(firstId))
        assertEquals(1, db.reviews().countAllSince(0))
    }

    @Test
    fun failedUndoRollsBackRestoredStateAndKeepsLog() = runBlocking {
        repo.answer(card, Rating.Easy, 100, now)
        val beforeAnswer = db.reviews().state(card.id)
        val (id, _) = repo.answer(card, Rating.Again, 200, now + Fsrs.DAY)
        val afterAnswer = db.reviews().state(card.id)
        failLog("DELETE")
        expectSqlFailure { repo.undo(id) }
        assertEquals(afterAnswer, db.reviews().state(card.id))
        assertNotNull(db.reviews().log(id))
        allowLog()
        assertEquals(card, repo.undo(id))
        assertEquals(beforeAnswer, db.reviews().state(card.id))
        assertNull(db.reviews().log(id))
    }

    @Test
    fun failedUndoOfFirstAnswerDoesNotDeleteState() = runBlocking {
        val (id, _) = repo.answer(card, Rating.Good, 100, now)
        val afterAnswer = db.reviews().state(card.id)
        failLog("DELETE")
        expectSqlFailure { repo.undo(id) }
        assertEquals(afterAnswer, db.reviews().state(card.id))
        assertNotNull(db.reviews().log(id))
        allowLog()
        repo.undo(id)
        assertNull(db.reviews().state(card.id))
        assertEquals(0, db.reviews().countAllSince(0))
    }

    @Test
    fun concurrentAnswersReadAndUpdateStateSerially() = runBlocking {
        val answers = (0..1).map {
            async(Dispatchers.IO) { repo.answer(card, Rating.Good, 100, now) }
        }.awaitAll()
        assertEquals(listOf(1, 2), answers.map { it.second.reps }.sorted())
        assertEquals(2, db.reviews().state(card.id)!!.reps)
        assertEquals(2, db.reviews().countAllSince(0))
    }

    @Test
    fun optimizerCannotOverwriteChangedOrResetParameters() = runBlocking {
        val original = settings.current()
        val custom = Fsrs.encodeParams(Fsrs.DEFAULT_W.copyOf().apply { this[2] = 7.0 })
        settings.setFsrsParams(custom, 200, 1f)
        assertFalse(settings.setFsrsParamsIfUnchanged(original.fsrsParams, original.fsrsUpdatedAt, "stale", 300, 2f))
        assertEquals(custom, settings.current().fsrsParams)
        val personalized = settings.current()
        assertTrue(settings.setFsrsParamsIfUnchanged(personalized.fsrsParams, personalized.fsrsUpdatedAt, custom, 300, 2f))
        val beforeReset = settings.current()
        settings.setFsrsParams(null, 0, 0f)
        assertFalse(settings.setFsrsParamsIfUnchanged(beforeReset.fsrsParams, beforeReset.fsrsUpdatedAt, custom, 300, 2f))
        assertNull(settings.current().fsrsParams)
    }

    private suspend fun seedHistory(validationRating: Rating): List<ReviewEvent> {
        val events = (0 until 200).flatMap { i ->
            listOf(
                ReviewEvent("card$i", Rating.Good.value, now),
                ReviewEvent("card$i", (if (i < 160) Rating.Good else validationRating).value, now + (i + 1) * Fsrs.DAY),
            )
        }
        for (e in events) {
            db.reviews().insertLog(ReviewLogEntity(
                cardId = e.cardId, deck = Deck.WORDS.id, rating = e.rating, reviewedAt = e.time,
                stateBefore = 2, durationMs = 100, prevStability = null, prevDifficulty = null,
                prevDue = null, prevLastReview = null, prevStep = null, prevReps = null, prevLapses = null,
            ))
        }
        return events
    }

    @Test
    fun rejectedTuningPreservesCurrentPersonalizedParametersAndMetadata() = runBlocking {
        val events = seedHistory(Rating.Again)
        val current = Fsrs.DEFAULT_W.copyOf().apply { this[2] = 7.0; this[8] = 2.8 }
        settings.setFsrsParams(Fsrs.encodeParams(current), 175, 1.5f)
        val before = settings.current()
        val outcome = repo.optimizeParams {} as OptimizeOutcome.Done
        assertEquals(FsrsOptimizer(events, current).validationLoss(current), outcome.lossBefore, 1e-12)
        assertFalse(outcome.improved)
        assertEquals(before, settings.current())
    }

    @Test
    fun acceptedTuningStoresExactlyTheValidatedParameters() = runBlocking {
        seedHistory(Rating.Good)
        val outcome = repo.optimizeParams {} as OptimizeOutcome.Done
        assertTrue(outcome.improved)
        val saved = settings.current()
        assertEquals(Fsrs.encodeParams(outcome.params), saved.fsrsParams)
        assertEquals(outcome.reviews, saved.fsrsReviews)
        assertEquals(outcome.improvementPercent.toFloat(), saved.fsrsImprovement, 0f)
    }

    @Test
    fun changingParametersDuringOptimizationDiscardsItsResult() = runBlocking {
        seedHistory(Rating.Good)
        val replacement = Fsrs.encodeParams(Fsrs.DEFAULT_W.copyOf().apply { this[2] = 7.0 })
        var changed = false
        val outcome = repo.optimizeParams {
            if (!changed) {
                changed = true
                runBlocking { settings.setFsrsParams(replacement, 200, 1f) }
            }
        }
        assertEquals(OptimizeOutcome.Superseded, outcome)
        assertEquals(replacement, settings.current().fsrsParams)
    }

    private fun viewModel(): StudyViewModel {
        lateinit var vm: StudyViewModel
        instrumentation.runOnMainSync {
            vm = StudyViewModel(repo, com.marat.albion.data.repo.StudySource.Dictionary(Deck.WORDS))
            store.put("study", vm)
        }
        return vm
    }

    private suspend fun StudyViewModel.awaitState(predicate: (StudyState) -> Boolean): StudyState =
        withTimeout(10_000) { state.first(predicate) }

    @Test
    fun failedSaveKeepsCardOnScreenAndAllowsRetry() = runBlocking {
        val vm = viewModel()
        vm.awaitState { !it.loading }
        failLog("INSERT")
        instrumentation.runOnMainSync { vm.reveal(); vm.answer(Rating.Easy) }
        val failed = vm.awaitState { it.error != null }
        assertEquals(card.id, failed.current!!.card.id)
        assertTrue(failed.revealed)
        assertEquals(0, failed.answered)
        assertNull(db.reviews().state(card.id))
        allowLog()
        instrumentation.runOnMainSync { vm.dismissError(); vm.answer(Rating.Easy) }
        val saved = vm.awaitState { it.finished }
        assertEquals(1, saved.answered)
        assertEquals(1, db.reviews().countAllSince(0))
    }

    @Test
    fun failedUndoKeepsUndoEntryForRetry() = runBlocking {
        val vm = viewModel()
        vm.awaitState { !it.loading }
        instrumentation.runOnMainSync { vm.reveal(); vm.answer(Rating.Easy) }
        vm.awaitState { it.finished }
        failLog("DELETE")
        instrumentation.runOnMainSync { vm.undo() }
        val failed = vm.awaitState { it.error != null }
        assertTrue(failed.canUndo)
        assertEquals(1, failed.answered)
        assertEquals(1, db.reviews().countAllSince(0))
        allowLog()
        instrumentation.runOnMainSync { vm.dismissError(); vm.undo() }
        val undone = vm.awaitState { it.current != null && it.answered == 0 }
        assertEquals(card.id, undone.current!!.card.id)
        assertNull(db.reviews().state(card.id))
        assertEquals(0, db.reviews().countAllSince(0))
    }
}
