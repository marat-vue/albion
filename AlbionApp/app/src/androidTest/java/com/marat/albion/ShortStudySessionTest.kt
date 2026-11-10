package com.marat.albion

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.fsrs.CardState
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.repo.StudyRepository
import com.marat.albion.data.settings.PracticeOptions
import com.marat.albion.data.settings.SettingsRepository
import com.marat.albion.ui.study.StudyState
import com.marat.albion.ui.study.StudyViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite/FSRS and an isolated DataStore; never changes the learner's data. */
@RunWith(AndroidJUnit4::class)
class ShortStudySessionTest {
    private lateinit var db: AlbionDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: StudyRepository
    private lateinit var preferencesFile: File
    private lateinit var settingsScope: CoroutineScope
    private val models = ViewModelStore()

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AlbionDatabase::class.java).build()
        preferencesFile = File(context.cacheDir, "ux-${UUID.randomUUID()}.preferences_pb")
        settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = settingsScope) { preferencesFile })
        repo = StudyRepository(db, settings, Json)
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { models.clear() }
        settingsScope.coroutineContext[Job]?.cancelAndJoin()
        db.close()
        preferencesFile.delete()
        Unit
    }

    private suspend fun seed(count: Int) {
        db.cards().upsert((1..count).map { n ->
            CardEntity("card-$n", Deck.WORDS.id, n, "word-$n", "", "noun", "A1", "", "", "", "", null, null)
        })
    }

    private suspend fun session(limit: Int? = null): StudyViewModel {
        val vm = withContext(Dispatchers.Main) {
            StudyViewModel(repo, com.marat.albion.data.repo.StudySource.Dictionary(Deck.WORDS), limit).also { models.put("study-${UUID.randomUUID()}", it) }
        }
        await(vm) { !it.loading }
        return vm
    }

    private suspend fun await(vm: StudyViewModel, predicate: (StudyState) -> Boolean): StudyState =
        withTimeout(10_000) { vm.state.first(predicate) }

    private suspend fun answer(vm: StudyViewModel, rating: Rating): StudyState {
        val expected = vm.state.value.answered + 1
        withContext(Dispatchers.Main) { vm.reveal(); vm.answer(rating) }
        return await(vm) { it.answered == expected && it.canUndo && (it.finished || !it.revealed) }
    }

    @Test
    fun limitStopsAfterSavedAnswersAndUndoReopensTheLastCard() = runBlocking {
        seed(4)
        val vm = session(2)
        answer(vm, Rating.Easy)
        val lastId = vm.state.value.current!!.card.id
        val finished = answer(vm, Rating.Again)
        assertTrue(finished.finished)
        assertNull(finished.current)
        assertEquals(2, finished.answered)
        assertEquals(1f, finished.progress, 0f)
        assertEquals(2, db.reviews().countAllSince(0))
        assertEquals(CardState.LEARNING, repo.memory(lastId).state)
        assertEquals(2, repo.buildQueue(Deck.WORDS).news.size)
        withContext(Dispatchers.Main) { vm.answer(Rating.Easy); vm.undo() }
        val undone = await(vm) { it.answered == 1 && it.current?.card?.id == lastId && !it.revealed }
        assertFalse(undone.finished)
        assertEquals(1, db.reviews().countAllSince(0))
        assertEquals(CardState.NEW, repo.memory(lastId).state)
        assertTrue(answer(vm, Rating.Easy).finished)
        assertEquals(2, db.reviews().countAllSince(0))
        assertEquals(2, repo.buildQueue(Deck.WORDS).news.size)
    }

    @Test
    fun repeatedLearningAnswersCountTowardsTheLimitAndKeepTheirSchedule() = runBlocking {
        seed(1)
        val vm = session(2)
        val first = answer(vm, Rating.Again)
        assertFalse(first.finished)
        assertEquals("card-1", first.current!!.card.id)
        assertEquals(0.5f, first.progress, 0f)
        val second = answer(vm, Rating.Again)
        assertTrue(second.finished)
        assertEquals(2, second.answered)
        assertEquals(2, db.reviews().countAllSince(0))
        assertEquals(CardState.LEARNING, repo.memory("card-1").state)
        assertTrue(repo.memory("card-1").due > System.currentTimeMillis())
    }

    @Test
    fun naturalExhaustionCanFinishBeforeTheLimit() = runBlocking {
        seed(1)
        val vm = session(10)
        val result = answer(vm, Rating.Easy)
        assertTrue(result.finished)
        assertFalse(result.limitReached)
        assertEquals(1, result.answered)
        assertEquals(1, db.reviews().countAllSince(0))
    }

    @Test
    fun ordinaryDeckSessionsStillRunThroughTheWholeQueue() = runBlocking {
        seed(3)
        val vm = session()
        assertNull(vm.state.value.answerLimit)
        assertFalse(answer(vm, Rating.Easy).finished)
        assertFalse(answer(vm, Rating.Easy).finished)
        assertTrue(answer(vm, Rating.Easy).finished)
        assertEquals(3, db.reviews().countAllSince(0))
    }

    @Test
    fun practiceSettingsSurviveStoreReopeningAndStayIndependentPerExercise() = runBlocking {
        val words = PracticeOptions(deckId = Deck.PHRASAL.id, count = 20)
        val grammar = PracticeOptions(count = 30, level = "B1")
        settings.setShortSessionAnswers(5)
        settings.savePracticeOptions("TYPE_ANSWER", words)
        settings.savePracticeOptions("GRAMMAR_CHOICE", grammar, markAsLast = false)
        settingsScope.coroutineContext[Job]?.cancelAndJoin()
        settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = settingsScope) { preferencesFile })
        assertEquals(5, settings.current().shortSessionAnswers)
        assertEquals(words, settings.practiceOptions("TYPE_ANSWER").first())
        assertEquals(grammar, settings.practiceOptions("GRAMMAR_CHOICE").first())
        assertEquals("TYPE_ANSWER", settings.lastPractice.first())
        settings.savePracticeOptions("GRAMMAR_CHOICE", grammar)
        assertEquals("GRAMMAR_CHOICE", settings.lastPractice.first())
    }
}
