package com.marat.albion.data.content

/**
 * Переименованные термины встроенных словарей. id карточки = словарь + термин + номер значения,
 * поэтому при импорте память FSRS, история, статистика игр и коллекции переносятся
 * со старого id на новый. Список только дописывается: старые записи нужны тем, кто обновляется
 * через несколько версий.
 */
object TermRenames {
    /** 2026-11: words.xlsx переведён на британское -ise. */
    private val words = listOf(
        "apologize" to "apologise", "authorize" to "authorise", "characterize" to "characterise",
        "civilization" to "civilisation", "criticize" to "criticise", "emphasize" to "emphasise",
        "globalization" to "globalisation", "maximize" to "maximise", "minimize" to "minimise",
        "mobilize" to "mobilise", "organization" to "organisation", "organizational" to "organisational",
        "organize" to "organise", "organized" to "organised", "organizer" to "organiser",
        "prioritize" to "prioritise", "realization" to "realisation", "realize" to "realise",
        "recognize" to "recognise", "specialize" to "specialise", "specialized" to "specialised",
        "stabilize" to "stabilise", "summarize" to "summarise", "utilize" to "utilise",
    ).toMap()

    fun forDeck(deck: Deck): Map<String, String> = if (deck == Deck.WORDS) words else emptyMap()

    /**
     * Пары «старый id → новый id» для карточек, которые есть в базе под старым термином
     * и появились в таблице под новым. Номер значения сохраняется.
     */
    fun moves(deck: Deck, existingIds: Collection<String>, newIds: Set<String>): List<Pair<String, String>> {
        val renames = forDeck(deck)
        if (renames.isEmpty()) return emptyList()
        val prefix = "${deck.id}:"
        return existingIds.mapNotNull { old ->
            if (old in newIds || !old.startsWith(prefix)) return@mapNotNull null
            val hash = old.lastIndexOf('#').takeIf { it > prefix.length } ?: return@mapNotNull null
            val renamed = renames[old.substring(prefix.length, hash)] ?: return@mapNotNull null
            val new = prefix + renamed + old.substring(hash)
            if (new in newIds) old to new else null
        }
    }
}
