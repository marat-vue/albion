package com.marat.albion.data.grammar

/** Ответ, который пользователь отметил «Мой ответ тоже верен», — кандидат в альтернативы grammar_tasks.xlsx. */
data class OwnAnswer(val taskId: String, val answer: String) {
    /** gt:1198 → «№1198», как в таблице заданий. */
    val taskNumber: String get() = "№" + taskId.removePrefix("gt:")
}

/** Журнал хранится строкой в DataStore: «id<TAB>ответ» по строке, новые в конце. */
object OwnAnswerLog {
    const val LIMIT = 300

    fun parse(raw: String?): List<OwnAnswer> = raw.orEmpty().lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.lastIndex) null else OwnAnswer(line.substring(0, tab), line.substring(tab + 1))
    }

    /** Повтор того же ответа к тому же заданию переносится в конец; старые записи сверх [LIMIT] отбрасываются. */
    fun append(raw: String?, taskId: String, answer: String): String {
        val clean = answer.replace(Regex("\\s+"), " ").trim()
        val id = taskId.replace(Regex("\\s+"), "")
        if (clean.isEmpty() || id.isEmpty()) return raw.orEmpty()
        val entries = parse(raw).filterNot { it.taskId == id && it.answer == clean } + OwnAnswer(id, clean)
        return format(entries.takeLast(LIMIT))
    }

    fun format(entries: List<OwnAnswer>): String = entries.joinToString("\n") { "${it.taskId}\t${it.answer}" }

    /** Для копирования: «№1198<TAB>ответ», удобно вставить рядом с таблицей. */
    fun export(entries: List<OwnAnswer>): String = entries.joinToString("\n") { "${it.taskNumber}\t${it.answer}" }
}
