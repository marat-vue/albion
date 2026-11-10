package com.marat.albion.data.repo

import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.db.CollectionWordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Коллекции пользователя: создание, переименование и состав. Расписание слов общее со словарями. */
class CollectionRepository(db: AlbionDatabase) {
    private val dao = db.collections()

    fun observeAll(): Flow<List<CollectionEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<CollectionEntity?> = dao.observe(id)

    /** Состав коллекции: пары «словарь — термин», см. [wordOf]. */
    fun observeWords(id: Long): Flow<Set<Pair<String, String>>> = dao.observeWords().map { rows ->
        rows.filter { it.collectionId == id }.mapTo(HashSet()) { it.deck to it.term }
    }.distinctUntilChanged()

    /** Коллекции, в которых уже есть слово [card]. */
    fun observeContaining(card: CardEntity): Flow<Set<Long>> {
        val (deck, term) = wordOf(card)
        return dao.observeWords().map { rows ->
            rows.filter { it.deck == deck && it.term == term }.mapTo(HashSet()) { it.collectionId }
        }.distinctUntilChanged()
    }

    suspend fun create(name: String, now: Long = System.currentTimeMillis()): Long =
        dao.insert(CollectionEntity(name = validName(name), createdAt = now))

    suspend fun rename(id: Long, name: String) = dao.rename(id, validName(name))

    suspend fun delete(id: Long) = dao.delete(id)

    /** Повторное добавление ничего не меняет. */
    suspend fun add(id: Long, cards: List<CardEntity>, now: Long = System.currentTimeMillis()) {
        if (cards.isEmpty()) return
        dao.addWords(cards.map { card ->
            val (deck, term) = wordOf(card)
            CollectionWordEntity(id, deck, term, now)
        }.distinct())
    }

    suspend fun remove(id: Long, card: CardEntity) {
        val (deck, term) = wordOf(card)
        dao.removeWord(id, deck, term)
    }

    suspend fun setContains(id: Long, card: CardEntity, contains: Boolean) =
        if (contains) add(id, listOf(card)) else remove(id, card)

    companion object {
        const val MAX_NAME = 60

        fun wordOf(card: CardEntity) = card.deck to WordCards.key(card.term)

        fun validName(name: String): String {
            val clean = name.trim().replace(Regex("\\s+"), " ")
            require(clean.isNotEmpty()) { "Введите название коллекции." }
            require(clean.length <= MAX_NAME) { "Сократите название до $MAX_NAME символов." }
            return clean
        }
    }
}
