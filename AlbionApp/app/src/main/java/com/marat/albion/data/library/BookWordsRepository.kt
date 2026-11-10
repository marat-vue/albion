package com.marat.albion.data.library

import androidx.room.withTransaction
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.repo.WordCards
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

@Serializable
data class BookWordSource(val bookId: String, val title: String, val chapter: String)

data class BookWordDraft(
    val term: String,
    val meaning: String,
    val example: String,
    val source: BookWordSource,
    val ipa: String = "",
    val pos: String = "word",
    val meaningEn: String = "",
    val level: String? = null,
)

data class SavedBookWord(val card: CardEntity, val added: Boolean, val undoToken: String? = null)

/** Личные карточки живут в той же таблице и используют обычную очередь FSRS. */
class BookWordsRepository(private val db: AlbionDatabase, private val json: Json) {
    // Only recent additions in this process can be undone from the reader's notification.
    private data class PendingAdd(val token: String, val activity: Triple<Int, Int, Int>)
    private val pendingAdds = LinkedHashMap<String, PendingAdd>()

    private suspend fun activity(cardId: String): Triple<Int, Int, Int> {
        val ids = db.cards().wordMembers(cardId).map { it.id }
        return Triple(ids.chunked(500).sumOf { db.reviews().countLogsFor(it) },
            db.cards().gameStats().filter { it.cardId in ids }.sumOf { it.correct + it.wrong },
            ids.chunked(500).sumOf { db.reviews().statesFor(it).sumOf { state -> state.reps } })
    }
    fun observeTerm(term: String): Flow<List<CardEntity>> =
        db.cards().observeWithPrefix(Deck.BOOK_WORDS.id, termPrefix(term)).map(WordCards::group)

    /** Edit a personal meaning in place: its identity and learning history stay intact. */
    suspend fun updateMeaning(cardId: String, meaning: String, example: String): CardEntity = db.withTransaction {
        val current = requireNotNull(db.cards().get(cardId)) { "Карточка не найдена" }
        require(current.deck == Deck.BOOK_WORDS.id) { "Можно редактировать только личные слова" }
        val cleaned = clean(meaning)
        require(cleaned.isNotBlank() && cleaned.length <= 2000) { "Введите перевод до 2000 символов" }
        require(example.length <= 4000) { "Сократите пример до 4000 символов" }
        require(db.cards().wordMembers(cardId).none {
            it.id != cardId && key(it.meaningRu).trimEnd('.', '!', '?', ';') == key(cleaned).trimEnd('.', '!', '?', ';')
        }) { "Это значение уже есть у слова" }
        val updated = current.copy(meaningRu = cleaned, example1 = example.trim(),
            meaningEn = if (cleaned == current.meaningRu) current.meaningEn else "")
        db.cards().upsert(listOf(updated))
        pendingAdds.remove(cardId)
        updated
    }

    suspend fun remove(cardId: String): Boolean = db.withTransaction {
        val card = db.cards().get(cardId) ?: return@withTransaction false
        if (card.deck != Deck.BOOK_WORDS.id) return@withTransaction false
        val members = db.cards().wordMembers(cardId)
        members.forEach {
            pendingAdds.remove(it.id)
            db.reviews().deleteState(it.id)
            db.reviews().deleteCardLogs(it.id)
        }
        db.cards().delete(members.map { it.id })
        val (deck, term) = com.marat.albion.data.repo.CollectionRepository.wordOf(card)
        db.collections().forgetWord(deck, term)
        true
    }

    suspend fun undoAdd(saved: SavedBookWord): Boolean = db.withTransaction {
        if (!saved.added || saved.undoToken == null || saved.card.deck != Deck.BOOK_WORDS.id ||
            pendingAdds[saved.card.id]?.token != saved.undoToken) return@withTransaction false
        val current = db.cards().get(saved.card.id)
        if (current != saved.card || db.cards().hasLearningActivity(saved.card.id) ||
            pendingAdds[saved.card.id]?.activity != activity(saved.card.id)) return@withTransaction false
        db.cards().delete(listOf(saved.card.id))
        pendingAdds.remove(saved.card.id)
        true
    }

    suspend fun add(draft: BookWordDraft): SavedBookWord {
        val term = clean(draft.term)
        val meaning = clean(draft.meaning)
        require(term.isNotBlank() && term.length <= 200) { "Введите слово или выражение до 200 символов" }
        require(meaning.isNotBlank() && meaning.length <= 2000) { "Введите перевод до 2000 символов" }
        require(draft.example.length <= 4000) { "Сократите пример до 4000 символов" }
        val baseId = termPrefix(term) + digest(key(meaning).trimEnd('.', '!', '?', ';'))
        return db.withTransaction {
            val existing = db.cards().inDeck(Deck.BOOK_WORDS.id).firstOrNull {
                key(it.term) == key(term) && key(it.meaningRu).trimEnd('.', '!', '?', ';') == key(meaning).trimEnd('.', '!', '?', ';')
            }
            if (existing != null) return@withTransaction SavedBookWord(existing, false)
            val id = if (db.cards().get(baseId) == null) baseId else "$baseId:${UUID.randomUUID()}"
            val card = CardEntity(
                id = id, deck = Deck.BOOK_WORDS.id, position = db.cards().nextPosition(Deck.BOOK_WORDS.id),
                term = term, ipa = draft.ipa, pos = draft.pos.ifBlank { "word" }, level = draft.level,
                meaningEn = draft.meaningEn, meaningRu = meaning,
                example1 = draft.example.trim(), example2 = "", audio = null,
                extra = json.encodeToString(BookWordSource.serializer(), draft.source),
            )
            val added = db.cards().insertIfMissing(card) != -1L
            val token = if (added) UUID.randomUUID().toString().also {
                pendingAdds[id] = PendingAdd(it, activity(id))
                if (pendingAdds.size > 32) pendingAdds.remove(pendingAdds.keys.first())
            } else null
            SavedBookWord(if (added) card else requireNotNull(db.cards().get(id)), added, token)
        }
    }

    companion object {
        fun source(card: CardEntity): BookWordSource? =
            if (card.deck != Deck.BOOK_WORDS.id) null
            else card.extra?.let { runCatching { Json.decodeFromString(BookWordSource.serializer(), it) }.getOrNull() }

        fun key(text: String): String = clean(Normalizer.normalize(text, Normalizer.Form.NFC)).lowercase(Locale.ROOT)

        private fun clean(text: String) = text.trim().replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ")
        private fun termPrefix(term: String) = "${Deck.BOOK_WORDS.id}:${digest(key(term))}:"
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
