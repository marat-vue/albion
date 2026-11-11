package com.marat.albion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.db.CollectionWordEntity
import com.marat.albion.data.db.GameAnswerLogEntity
import com.marat.albion.data.db.GameCardStatEntity
import com.marat.albion.data.db.ReviewLogEntity
import com.marat.albion.data.db.ReviewStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** organize → organise: память FSRS, история, игры и коллекции переходят на новый id. */
@RunWith(AndroidJUnit4::class)
class TermRenameTest {
    private lateinit var db: AlbionDatabase
    private val old = "words:organize#0"
    private val new = "words:organise#0"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AlbionDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun learningHistoryFollowsTheRenamedTerm() = runBlocking {
        db.reviews().upsertState(ReviewStateEntity(old, Deck.WORDS.id, state = 2, step = 0, stability = 12.0, difficulty = 5.0,
            due = 2_000, lastReview = 1_000, reps = 4, lapses = 1))
        db.reviews().insertLog(ReviewLogEntity(cardId = old, deck = Deck.WORDS.id, rating = 3, reviewedAt = 1_000, stateBefore = 2,
            durationMs = 500, prevStability = null, prevDifficulty = null, prevDue = null, prevLastReview = null,
            prevStep = null, prevReps = null, prevLapses = null))
        db.cards().upsertGameStat(GameCardStatEntity(old, "TYPING", correct = 2, wrong = 1, streak = 0, lastSeen = 1_000))
        db.gameActivity().insert(GameAnswerLogEntity(game = "TYPING", itemId = old, correct = false, answeredAt = 1_000, durationMs = 100))
        val collection = db.collections().insert(CollectionEntity(name = "Work", createdAt = 0))
        db.collections().addWords(listOf(CollectionWordEntity(collection, Deck.WORDS.id, "organize", 0)))

        db.termRenames().moveCard(old, new)
        db.termRenames().moveCollectionTerm(Deck.WORDS.id, "organize", "organise")

        assertNull(db.reviews().state(old))
        assertEquals(12.0, db.reviews().state(new)!!.stability, 0.0)
        assertEquals(1, db.reviews().statesFor(listOf(new)).size)
        assertNotNull(db.cards().gameStat(new, "TYPING"))
        assertNull(db.cards().gameStat(old, "TYPING"))
        assertEquals(listOf(new), column("SELECT card_id FROM review_log"))
        assertEquals(listOf(new), column("SELECT item_id FROM game_answer_log"))
        assertEquals(listOf("organise"), db.collections().words().map { it.term })
    }

    private fun column(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
}
