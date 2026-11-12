package com.marat.albion

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.fsrs.Rating
import com.marat.albion.data.repo.StudyRepository
import com.marat.albion.data.settings.SettingsRepository
import com.marat.albion.ui.games.Game
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameProgressTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: AlbionDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: StudyRepository
    private val card = CardEntity("progress:cat", Deck.WORDS.id, 0, "cat", "", "noun", "A1",
        "an animal", "кошка", "The cat sleeps.", "", null, null)
    private fun task(kind: String) = GrammarTaskEntity("progress:$kind", 0, "A1", 1, "Test", kind,
        "She ___ here.", "is\nare", "is", "Use is.")

    @Before fun setUp() = runBlocking {
        val context = instrumentation.targetContext
        db = Room.inMemoryDatabaseBuilder(context, AlbionDatabase::class.java).build()
        settings = SettingsRepository(context)
        repo = StudyRepository(db, settings, Json)
        db.cards().upsert(listOf(card))
        db.grammarTasks().upsert(listOf(task("choice"), task("input")))
    }

    @After fun tearDown() { db.close() }

    @Test fun deckSearchMatchesTermsAndTranslationsAndKeepsFilters() = runBlocking {
        assertEquals(listOf(card), repo.browse(Deck.WORDS, "CAT", 0).first())
        assertEquals(listOf(card), repo.browse(Deck.WORDS, "кошка", 0).first())
        assertEquals(listOf(card), repo.browse(Deck.WORDS, "animal", 0).first())
        assertTrue(repo.browse(Deck.WORDS, "собака", 0).first().isEmpty())
        assertEquals(listOf(card), repo.browse(Deck.WORDS, "", 1).first())
        assertTrue(repo.browse(Deck.WORDS, "cat", 3).first().isEmpty())
    }

    @Test fun everyGameAndTopicQuestionContributesWithoutChangingFsrs() = runBlocking {
        val now = System.currentTimeMillis()
        Game.entries.filterNot { it.isGrammar }.forEach { game ->
            repo.recordGame(card.id, game.name, true, 1_000, now)
        }
        repo.recordGrammar(task("choice").id, false, "choice", 2_000, now)
        repo.recordGrammar(task("input").id, true, "input", 3_000, now)
        val s = withTimeout(5_000) { repo.observeStats().first() }
        val games = Game.entries.count { !it.isGrammar }
        val answers = games + 2
        assertEquals(answers, s.totalGameAnswers)
        assertEquals(answers, s.answersToday)
        assertEquals(answers, s.gameAnswersToday)
        assertEquals(answers, s.daily.single().count)
        assertEquals(1, s.streak)
        assertEquals(games * 1_000L + 5_000L, s.timeTodayMs)
        assertEquals((answers - 1f) / answers, s.gameAccuracy!!, 0.001f)
        assertEquals(answers, s.games.size)
        assertEquals(0, s.reviewsToday)
        assertEquals(0, s.totalReviews)
        assertNull(s.retention)
        assertNull(db.reviews().state(card.id))
    }

    @Test fun activityCombinesCardsAndGamesAcrossTheStudyDayBoundary() = runBlocking {
        val hour = settings.current().dayStartHour
        val now = System.currentTimeMillis()
        val start = repo.dayStart(now, hour)
        repo.answer(card, Rating.Good, 1_000, start - 1)
        repo.recordGame(card.id, Game.IRREGULAR.name, false, 2_000, now)
        repo.recordGrammar(task("input").id, true, "input", 3_000, now)
        val s = withTimeout(5_000) { repo.observeStats().first() }
        assertEquals(2, s.streak)
        assertEquals(2, s.answersToday)
        assertEquals(1, s.totalReviews)
        assertEquals(5_000L, s.timeTodayMs)
        assertEquals(mapOf(s.today - 1 to 1, s.today to 2), s.daily.associate { it.day to it.count })
        assertEquals(repo.studyDay(start - 1, hour), s.today - 1)
    }

    @Test fun concurrentAnswersCannotLoseCountsOrHistory() = runBlocking {
        (1..40).map { n -> async { repo.recordGame(card.id, Game.TYPE_ANSWER.name, n % 2 == 0) } }.awaitAll()
        val stat = db.cards().gameStat(card.id, Game.TYPE_ANSWER.name)!!
        assertEquals(20, stat.correct)
        assertEquals(20, stat.wrong)
        assertEquals(40, db.gameActivity().observeAnswersSince(0).first())
        assertEquals(40, db.gameActivity().observeTotals().first().single().answers)
    }

    @Test fun aFailedHistoryWriteRollsBackBothKindsOfTotals() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_game_log BEFORE INSERT ON game_answer_log
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        for (write in listOf<suspend () -> Unit>(
            { repo.recordGame(card.id, Game.IRREGULAR.name, false) },
            { repo.recordGrammar(task("choice").id, true, "choice") },
        )) {
            try { write(); fail("Expected a SQLite error") } catch (_: SQLiteException) { }
        }
        assertNull(db.cards().gameStat(card.id, Game.IRREGULAR.name))
        assertNull(db.grammarTasks().stat(task("choice").id))
        assertTrue(db.gameActivity().observeTotals().first().isEmpty())
        assertEquals(0, db.gameActivity().observeAnswersSince(0).first())
    }

    @Test fun migrationPreservesOldTotalsWithoutInventingDailyHistory() = runBlocking {
        val context = instrumentation.targetContext
        val name = "game-progress-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = instrumentation.context.assets.open("com.marat.albion.data.db.AlbionDatabase/8.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i)
                val tableName = table.getString("tableName")
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = table.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO game_card_stats VALUES ('old-card', 'IRREGULAR', 8, 2, 1, 1000)")
            old.execSQL("INSERT INTO grammar_tasks VALUES ('old-task', 0, 'A1', 1, 'Test', 'input', 'She ___ here.', '', 'is', '')")
            old.execSQL("INSERT INTO grammar_task_stats VALUES ('old-task', 3, 1, 1000)")
            old.version = 8
        }
        val migrated = Room.databaseBuilder(context, AlbionDatabase::class.java, name).build()
        try {
            val totals = migrated.gameActivity().observeTotals().first().associateBy { it.game }
            assertEquals(10, totals.getValue("IRREGULAR").answers)
            assertEquals(8, totals.getValue("IRREGULAR").correct)
            assertEquals(4, totals.getValue("GRAMMAR_GAP").answers)
            assertEquals(0, migrated.gameActivity().observeAnswersSince(0).first())
            assertTrue(migrated.gameActivity().observeDaily(0, 0).first().isEmpty())
            assertEquals(1, migrated.cards().gameStat("old-card", "IRREGULAR")!!.streak)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
