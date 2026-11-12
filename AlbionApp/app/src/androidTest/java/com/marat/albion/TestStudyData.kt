package com.marat.albion

import com.marat.albion.data.content.Deck
import kotlinx.coroutines.flow.first

/**
 * «Сегодня» берёт новые слова только из пользовательских коллекций; на чистой установке занятие пустое.
 * Тесты экранов, которым нужна кнопка «Начать», заводят небольшую коллекцию из словаря.
 * Возвращает id созданной коллекции (null, если у пользователя коллекции уже есть) — его удаляют в `@After`.
 */
suspend fun AppContainer.ensureStudyToday(): Long? {
    importer.importIfNeeded()
    if (collections.observeAll().first().isNotEmpty()) return null
    val words = database.cards().inDeck(Deck.WORDS.id).take(20)
    return collections.create("Тестовая коллекция").also { collections.add(it, words) }
}

/** Убирает коллекцию, созданную [ensureStudyToday], чтобы тест не оставлял данных в приложении. */
suspend fun AppContainer.removeTestCollection(id: Long?) {
    if (id != null) collections.delete(id)
}
