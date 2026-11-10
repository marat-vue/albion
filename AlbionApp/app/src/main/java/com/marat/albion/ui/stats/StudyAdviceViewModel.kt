package com.marat.albion.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marat.albion.AppContainer
import com.marat.albion.data.grammar.GrammarNextStep
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class StudyAdviceViewModel(c: AppContainer) : ViewModel() {
    private data class GrammarContext(
        val book: com.marat.albion.data.grammar.GrammarBook,
        val progress: Map<String, com.marat.albion.data.db.GrammarProgressEntity>,
        val quizTopics: Set<Int>, val lastTopic: String?,
    )
    private val grammar = combine(flow { emit(c.grammar.book()) }, c.grammar.observeProgress(),
        c.grammar.observeQuizTopics(), c.settings.settings) { book, progress, quizzes, s ->
        GrammarContext(book, progress.associateBy { it.topicId }, quizzes.toSet(), s.lastGrammarTopic)
    }
    val advice = combine(c.study.observeOverview(), c.study.observePracticeNeeds(), grammar) { study, needs, g ->
        StudyAdvicePolicy.choose(study.today, GrammarNextStep.recommend(g.book, g.progress, g.quizTopics,
            System.currentTimeMillis(), lastId = g.lastTopic), needs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
