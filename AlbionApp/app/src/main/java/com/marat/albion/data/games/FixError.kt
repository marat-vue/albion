package com.marat.albion.data.games

import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.grammar.GrammarPrompt

/**
 * «Исправьте ошибку»: в предложении одна ошибка в [error] (символы [sentence]). Сначала её
 * нужно найти касанием слова, затем вписать верный вариант из [corrections].
 */
data class FixTask(
    val id: String,
    val sentence: String,
    val error: IntRange,
    val corrections: List<String>,
    val correctSentence: String,
    val explanation: String,
    /** Тема учебника, если задание взято из grammar_tasks. */
    val topic: Int? = null,
) {
    val wrongText: String get() = sentence.substring(error)

    /** Слова предложения с их позициями — то, чего касается пользователь. */
    val tokens: List<Pair<String, IntRange>> get() = TOKEN.findAll(sentence).map { it.value to it.range }.toList()

    /** Касание слова, задевающего ошибку, — место найдено. */
    fun isError(token: IntRange): Boolean = token.first <= error.last && token.last >= error.first

    fun check(answer: String): Verdict = AnswerCheck.checkGrammar(answer, corrections)

    companion object {
        private val TOKEN = Regex("\\S+")
    }
}

object FixError {
    private val DASHES = setOf("—", "-", "–", "")

    /**
     * Задание с выбором варианта превращается в ошибку: в пропуск подставлен неверный вариант.
     * Пропуск должен быть один, а и верный, и неверный варианты — не «пусто» (нулевой артикль).
     */
    fun fromGrammar(task: GrammarTaskEntity, pick: (List<String>) -> String? = { it.randomOrNull() }): FixTask? {
        if (task.kind != "choice") return null
        val prompt = GrammarPrompt(task.prompt)
        if (prompt.gapCount != 1) return null
        val correct = task.acceptedAnswers.firstOrNull()?.trim() ?: return null
        if (correct in DASHES) return null
        val wrongOptions = task.optionList.map { it.trim() }
            .filter { it !in DASHES && !it.equals(correct, ignoreCase = true) }
        val wrong = pick(wrongOptions) ?: return null
        val before = prompt.textPart(0, wrong)
        val sentence = before + wrong + prompt.parts[1]
        val correctSentence = prompt.complete(correct) ?: return null
        if (sentence.equals(correctSentence, ignoreCase = true)) return null
        return FixTask(
            id = task.id, sentence = sentence, error = before.length until before.length + wrong.length,
            corrections = task.acceptedAnswers, correctSentence = correctSentence,
            explanation = task.explanation, topic = task.topic,
        )
    }

    /** Заготовка вида «I [am agree] with you.»: в скобках — ошибка. */
    internal fun parse(id: String, marked: String, corrections: List<String>, explanation: String): FixTask {
        val start = marked.indexOf('[')
        val end = marked.indexOf(']')
        require(start in 0 until end) { "Нет ошибки в скобках: $marked" }
        val sentence = marked.removeRange(end, end + 1).removeRange(start, start + 1)
        val error = start until end - 1
        return FixTask(id, sentence, error, corrections, sentence.replaceRange(error, corrections.first()), explanation)
    }

    /** Типичные ошибки русскоязычных: калька с русского, предлоги, времена, «неисчисляемые». */
    val typical: List<FixTask> by lazy {
        listOf(
            Triple("I [am agree] with you.", listOf("agree"), "Agree — глагол: «я согласен» по-английски I agree, без am."),
            Triple("He [have] two sisters.", listOf("has"), "Он, она, оно в Present Simple: has, а не have."),
            Triple("I [didn't went] there yesterday.", listOf("didn't go", "did not go"), "После did / didn't — первая форма: прошедшее время уже выражено did."),
            Triple("[How] do you call this in English?", listOf("What"), "«Как это называется» — What do you call…? How спрашивает о способе."),
            Triple("I am [interesting] in history.", listOf("interested"), "-ed — что вы чувствуете (interested), -ing — каким кажется предмет (interesting)."),
            Triple("She is good [in] maths.", listOf("at"), "Good at something — «хорошо умеет»."),
            Triple("It depends [from] the weather.", listOf("on"), "Depend on — «зависеть от»; from — калька с русского."),
            Triple("I [know] him for ten years.", listOf("have known"), "Состояние длится до сих пор — Present Perfect: I have known him for ten years."),
            Triple("Yesterday I [have seen] a great film.", listOf("saw"), "С yesterday и другими указаниями на законченное прошлое — Past Simple."),
            Triple("I'll call you when I [will get] home.", listOf("get"), "После when, if, as soon as о будущем — Present Simple, без will."),
            Triple("Can you [say] me the time?", listOf("tell"), "Tell somebody something; say что-то без адресата: say hello."),
            Triple("She gave me some useful [advices].", listOf("advice"), "Advice неисчисляемое: some advice, a piece of advice."),
            Triple("We [discussed about] the plan.", listOf("discussed"), "Discuss — без предлога: discuss the plan."),
            Triple("I [very like] this song.", listOf("really like"), "Very не ставят перед глаголом: I really like it или I like it very much."),
            Triple("He [said me] that he was tired.", listOf("told me"), "Say не принимает адресата без to: he told me / he said to me."),
            Triple("Let's meet [in] Monday.", listOf("on"), "С днями недели и датами — on: on Monday, on 5 May."),
            Triple("I was born [on] 1995.", listOf("in"), "С годами, месяцами и веками — in: in 1995."),
            Triple("The police [is] looking for him.", listOf("are"), "Police — множественное число: the police are."),
            Triple("I look forward to [see] you.", listOf("seeing"), "В look forward to to — предлог, после него -ing."),
            Triple("She [made] her homework.", listOf("did"), "Do homework, do the washing; make — создать: make a cake."),
            Triple("I went [to home] early.", listOf("home"), "Home после go, come, get — без to: go home."),
            Triple("If I [will have] time, I'll help you.", listOf("have"), "В условии первого типа — Present Simple: if I have time."),
            Triple("I [am living] here since 2010.", listOf("have been living", "have lived"), "Since / for до настоящего момента — Present Perfect (Continuous)."),
            Triple("There [is] many people here.", listOf("are"), "There are + множественное число."),
            Triple("It's [more better] now.", listOf("better"), "Better уже сравнительная степень, more не нужно."),
            Triple("He asked where [do I live].", listOf("I lived", "I live"), "В косвенном вопросе прямой порядок слов и без do: where I lived."),
            Triple("My friend [borrowed] me his bike.", listOf("lent"), "Lend — дать взаймы, borrow — взять взаймы."),
            Triple("Please [open] the light.", listOf("turn on", "switch on"), "Свет включают: turn on / switch on the light."),
            Triple("He's married [with] a doctor.", listOf("to"), "Be married to somebody."),
            Triple("I prefer tea [than] coffee.", listOf("to"), "Prefer one thing to another."),
            Triple("She [has 25 years].", listOf("is 25", "is 25 years old"), "Возраст — через be: she is 25."),
        ).mapIndexed { i, (marked, fix, why) -> parse("typical:$i", marked, fix, why) }
    }
}
