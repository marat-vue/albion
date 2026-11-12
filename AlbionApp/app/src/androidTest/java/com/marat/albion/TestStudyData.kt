package com.marat.albion

import com.marat.albion.data.content.Deck
import kotlinx.coroutines.flow.first

/**
 * «Сегодня» берёт новые слова только из пользовательских коллекций; на чистой установке занятие пустое.
 * Тесты экранов, которым нужна кнопка «Начать», заводят небольшую коллекцию из словаря.
 */
suspend fun AppContainer.ensureStudyToday() {
    importer.importIfNeeded()
    if (collections.observeAll().first().isNotEmpty()) return
    val words = database.cards().inDeck(Deck.WORDS.id).take(20)
    collections.add(collections.create("Тестовая коллекция"), words)
}
