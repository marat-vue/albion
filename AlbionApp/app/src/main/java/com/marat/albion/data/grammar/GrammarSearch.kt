package com.marat.albion.data.grammar

/** Индексируем правило и примеры; ответы и каталог источников не попадают в выдержки. */
object GrammarSearch {
    fun text(document: MdDocument): String {
        fun flatten(blocks: List<MdBlock>): List<String> = blocks.flatMap { block ->
            when (block) {
                is MdParagraph -> listOf(block.text)
                is MdHeading -> listOf(block.text)
                is MdList -> block.items.flatMap { flatten(it.blocks) }
                is MdTable -> block.rows.flatten()
                is MdCallout -> if (block.kind == "success" || block.title.contains("Источни")) emptyList() else flatten(block.blocks)
                is MdQuote -> flatten(block.blocks)
                else -> emptyList()
            }
        }
        // Раздел заданий в уроках назывался «Проверь себя», теперь — «Проверьте себя».
        val theory = document.blocks.takeWhile { it !is MdHeading || it.text !in setOf("Проверьте себя", "Проверь себя") }
        return plain(flatten(theory).joinToString(" "))
    }

    fun matches(topic: Topic, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        if (q.toIntOrNull() == topic.number && topic.number != null) return true
        val index = normalize("${topic.title} ${topic.keywords} ${topic.searchText}")
        return q.split(' ').all { it in index }
    }

    fun snippet(topic: Topic, query: String): String {
        val body = topic.searchText
        if (body.isBlank()) return ""
        val terms = normalize(query).split(' ').filter(String::isNotBlank)
        val position = terms.map { normalize(body).indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        var start = (position - 35).coerceAtLeast(0)
        if (start > 0) start = body.indexOf(' ', start).takeIf { it >= 0 }?.plus(1) ?: start
        val end = (start + 150).coerceAtMost(body.length)
        return (if (start > 0) "…" else "") + body.substring(start, end).trim() + if (end < body.length) "…" else ""
    }

    private fun plain(text: String) = text
        .replace(Regex("\\[\\[([^]|]+)\\|([^]]+)]]"), "$2")
        .replace(Regex("\\[\\[([^]]+)]]"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("==|~~"), "")
        .replace(Regex("[*`_#]"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun normalize(text: String) = text.lowercase().replace('ё', 'е')
        .replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ").trim()
}
