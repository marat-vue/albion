package com.marat.albion.ui.stats

import androidx.navigation3.runtime.NavKey
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.PracticeNeed
import com.marat.albion.data.grammar.GrammarNextStep
import com.marat.albion.data.repo.TodaySummary
import com.marat.albion.ui.games.Game
import com.marat.albion.ui.library.pluralRu
import com.marat.albion.ui.nav.GameRoute
import com.marat.albion.ui.nav.StudyRoute
import com.marat.albion.ui.nav.TopicQuizRoute
import com.marat.albion.ui.nav.TopicRoute

data class StudyAdvice(val title: String, val reason: String, val action: String, val destination: NavKey)

/** Explainable priorities: scheduled study, current mistakes, then new material. */
object StudyAdvicePolicy {
    fun choose(today: TodaySummary, grammar: GrammarNextStep.Step?, needs: List<PracticeNeed>): StudyAdvice? {
        val due = today.due
        val available = today.toStudy
        if (due > 0) return StudyAdvice("Вернуться к карточкам",
            "К повторению: $due ${pluralRu(due, "карточка", "карточки", "карточек")}. Начните с короткого занятия; остальные останутся на потом.",
            "Короткое занятие", StudyRoute(answerLimit = minOf(10, available)))
        if (grammar?.kind == GrammarNextStep.Kind.Review) return grammarAdvice(grammar)
        val pending = needs.sortedWith(compareByDescending<PracticeNeed> { it.count }.thenByDescending { it.lastSeen }
            .thenBy { it.game }.thenBy { it.deck }).firstNotNullOfOrNull { need ->
            val game = Game.entries.firstOrNull { it.name == need.game } ?: return@firstNotNullOfOrNull null
            val deck = Deck.byId(need.deck)?.takeIf { it in game.decks } ?: return@firstNotNullOfOrNull null
            if (need.count <= 0 || game !in listOf(Game.TYPE_ANSWER, Game.IRREGULAR, Game.PRONUNCIATION, Game.SHADOWING)) null
            else Triple(need, game, deck)
        }
        if (pending != null) {
            val (need, game, deck) = pending
            val scope = if (deck.title == game.title) "" else "${deck.title}: "
            return StudyAdvice(game.title,
                "$scope${need.count} ${pluralRu(need.count, "слово", "слова", "слов")} ещё в списке ошибок. Закрепите их короткой практикой.",
                "Разобрать ошибки", GameRoute(game, deck, onlyMistakes = true, count = 10))
        }
        if (grammar?.kind == GrammarNextStep.Kind.Check) return grammarAdvice(grammar)
        if (available > 0) return StudyAdvice("Небольшой шаг сегодня",
            "В ваших коллекциях есть новые слова. Короткое занятие поможет двигаться вперёд без перегрузки.",
            "Начать занятие", StudyRoute(answerLimit = minOf(10, available)))
        return grammar?.let(::grammarAdvice)
    }

    private fun grammarAdvice(step: GrammarNextStep.Step): StudyAdvice {
        val t = step.topic
        val destination = if (step.kind == GrammarNextStep.Kind.Read || t.number == null) TopicRoute(t.id)
            else TopicQuizRoute(t.number, t.id, t.title)
        return StudyAdvice("${step.label}: ${t.title}", step.reason, step.action, destination)
    }
}
