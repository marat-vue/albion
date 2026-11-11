package com.marat.albion.data.grammar

/**
 * [excluded] — вопросы, где пользователь отметил «Мой ответ тоже верен»: они не входят
 * ни в верные, ни в [total]. Если таких больше половины, тест не меняет освоение.
 */
data class GrammarQuizResult(val correct: Int, val total: Int, val fullAttempt: Boolean, val excluded: Int = 0) {
    init {
        require(total >= 0 && correct in 0..total && excluded >= 0)
    }

    val updatesMastery: Boolean get() = fullAttempt && total > 0 && excluded <= total
    val passed: Boolean get() = updatesMastery && correct.toLong() * 10 >= total.toLong() * 7
}

/** Полный тест и повтор ошибок — разные попытки. Каждый вопрос учитывается один раз. */
class GrammarQuizSession(taskIds: List<String>, private val fullAttempt: Boolean) {
    private val expected = taskIds.toSet()
    private val answers = LinkedHashMap<String, Boolean>()
    private val excluded = HashSet<String>()

    init { require(expected.size == taskIds.size) }

    fun record(taskId: String, correct: Boolean): Boolean {
        if (taskId !in expected || answered(taskId)) return false
        answers[taskId] = correct
        return true
    }

    /** «Мой ответ тоже верен»: вопрос пройден, но не учитывается в результате. */
    fun exclude(taskId: String): Boolean {
        if (taskId !in expected || answered(taskId)) return false
        excluded += taskId
        return true
    }

    fun answered(taskId: String): Boolean = taskId in answers || taskId in excluded

    val excludedCount: Int get() = excluded.size

    fun result(): GrammarQuizResult? =
        if (expected.isNotEmpty() && answers.size + excluded.size == expected.size) {
            GrammarQuizResult(answers.values.count { it }, answers.size, fullAttempt, excluded.size)
        } else null
}
