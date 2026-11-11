package com.marat.albion.data.library

import android.content.res.AssetManager
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.DictionaryEntity
import com.marat.albion.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Статья словаря для показа: значения уже разбиты. */
data class DictEntry(val headword: String, val pos: String, val senses: List<String>) {
    /** Часть речи по-русски, как в карточках. */
    val posLabel: String get() = POS_RU[pos] ?: ""

    companion object {
        private val POS_RU = mapOf(
            "n" to "сущ.", "v" to "гл.", "adj" to "прил.", "adv" to "нареч.", "pn" to "имя собств.",
            "phraseologicalUnit" to "фразеологизм", "interjection" to "межд.", "proverb" to "пословица",
            "numeral" to "числ.", "preposition" to "предлог", "pronoun" to "местоим.", "conjunction" to "союз",
            "determiner" to "определитель", "prefix" to "приставка", "suffix" to "суффикс", "particle" to "частица",
        )
    }
}

/**
 * Офлайн-словарь EN → RU, встроенный в APK (assets/dictionary/eng-rus.tsv, FreeDict на основе
 * Викисловаря, CC BY-SA 3.0). При первом запуске переносится в Room — около 62 тысяч статей,
 * дальше поиск слова занимает миллисекунды и не требует сети.
 */
class Dictionary(
    private val assets: AssetManager,
    private val db: AlbionDatabase,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    private val dao = db.dictionary()
    private val mutex = Mutex()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Неправильные формы → инфинитив (went → go), из колоды неправильных глаголов. */
    private var irregular: Map<String, String>? = null

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_ready.value) return@withLock
            if (settings.dictionaryVersion() != VERSION || dao.count() == 0) {
                dao.clear()
                val batch = ArrayList<DictionaryEntity>(BATCH)
                assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.split('\t')
                        if (parts.size < 3 || parts[2].isBlank()) continue
                        batch += DictionaryEntity(term = parts[0].lowercase(), headword = parts[0], pos = parts[1], text = parts[2])
                        if (batch.size == BATCH) { dao.insert(batch); batch.clear() }
                    }
                }
                if (batch.isNotEmpty()) dao.insert(batch)
                settings.setDictionaryVersion(VERSION)
            }
            _ready.value = true
        }
    }

    /** Ищет слово с учётом окончаний (-s, -ed, -ing…) и неправильных форм. */
    suspend fun lookup(word: String): List<DictEntry> {
        ensureLoaded()
        val candidates = candidates(word)
        if (candidates.isEmpty()) return emptyList()
        val rank = candidates.withIndex().associate { (i, w) -> w to i }
        return dao.find(candidates)
            .sortedWith(
                compareBy<DictionaryEntity>(
                    { rank[it.term] ?: Int.MAX_VALUE },
                    // «it» — местоимение, а не «IT»: статьи с совпадающим регистром выше.
                    { if (it.headword == word.trim() || it.headword == it.term) 0 else 1 },
                    { POS_ORDER.indexOf(it.pos).let { i -> if (i < 0) 99 else i } },
                )
            )
            .take(MAX_ENTRIES)
            .map { DictEntry(it.headword, it.pos, it.text.split("; ").filter(String::isNotBlank)) }
    }

    /** Кандидаты для поиска в словаре и в карточках. */
    suspend fun candidates(word: String): List<String> {
        val base = wordCandidates(word)
        if (base.isEmpty()) return base
        // Пока колоды не импортированы, карта пуста — тогда не кэшируем её.
        val forms = irregular ?: loadIrregular().also { if (it.isNotEmpty()) irregular = it }
        return (base + base.mapNotNull { forms[it] }).distinct()
    }

    /** Неправильные формы → инфинитив (для подсветки слов в книгах). */
    suspend fun irregularForms(): Map<String, String> = irregular ?: loadIrregular().also { if (it.isNotEmpty()) irregular = it }

    private suspend fun loadIrregular(): Map<String, String> {
        val map = HashMap<String, String>()
        db.lookup().irregularForms().forEach { extra ->
            val data = runCatching { json.decodeFromString(IrregularData.serializer(), extra) }.getOrNull() ?: return@forEach
            val infinitive = data.forms.firstOrNull()?.variants?.firstOrNull()?.text?.lowercase() ?: return@forEach
            data.forms.drop(1).flatMap { it.variants }.forEach { v -> map.putIfAbsent(v.text.lowercase(), infinitive) }
        }
        return map
    }

    companion object {
        private const val ASSET = "dictionary/eng-rus.tsv"
        /** Меняется, когда обновляется файл словаря — тогда таблица перезаливается. */
        private const val VERSION = 1
        private const val BATCH = 2000
        private const val MAX_ENTRIES = 4
        /** Служебные части речи первыми: у «while», «to», «it» они главные, а у обычных слов их просто нет. */
        private val POS_ORDER = listOf(
            "pronoun", "preposition", "conjunction", "determiner", "particle",
            "v", "n", "adj", "adv", "phraseologicalUnit", "interjection", "numeral",
        )
    }
}

/**
 * Возможные словарные формы слова: «studies» → study, «stopped» → stop, «making» → make.
 * Порядок важен — побеждает первая форма, найденная в словаре, поэтому он следует морфологии:
 * notes → note (не not), hoped → hope (не hop), uses → use (не us).
 */
fun wordCandidates(word: String): List<String> {
    val w = word.lowercase().trim().replace('’', '\'').removeSuffix("'s").trim('\'', '"', '“', '”', '‘')
    if (w.length < 2) return emptyList()
    val c = linkedSetOf(w)
    // «flies» → fly, но «ties» → tie: у коротких слов -ies — это основа на -ie плюс -s.
    if (w.length > 4 && w.endsWith("ies")) c += w.dropLast(3) + "y"
    if (w.endsWith("es")) {
        val stem = w.dropLast(2)
        // -es после шипящих (classes, watches, boxes), иначе это основа на -e плюс -s: toes, goes → goe не слово.
        if (SIBILANT_ENDINGS.any(stem::endsWith)) { c += stem; c += w.dropLast(1) } else { c += w.dropLast(1); c += stem }
    } else if (w.endsWith("s") && !w.endsWith("ss")) c += w.dropLast(1)
    if (w.length > 4 && w.endsWith("ied")) c += w.dropLast(3) + "y"
    // tied, died, owed: у двухбуквенной основы -ed почти всегда присоединено к -e.
    if (w.endsWith("ed")) c += stemOrStemE(w.dropLast(2), preferE = w.length == 4)
    if (w.endsWith("ing")) c += stemOrStemE(w.dropLast(3))
    if (w.length > 4 && w.endsWith("ing") && w[w.length - 4] == w[w.length - 5]) c += w.dropLast(4)
    if (w.length > 3 && w.endsWith("ed") && w[w.length - 3] == w[w.length - 4]) c += w.dropLast(3)
    if (w.endsWith("ier")) c += w.dropLast(3) + "y"
    if (w.endsWith("iest")) c += w.dropLast(4) + "y"
    if (w.endsWith("er")) c += stemOrStemE(w.dropLast(2))
    if (w.endsWith("est")) c += stemOrStemE(w.dropLast(3))
    if (w.endsWith("ly")) c += w.dropLast(2)
    if (w.endsWith("ily")) c += w.dropLast(3) + "y"
    return c.filter { it.length >= 2 }
}

private val SIBILANT_ENDINGS = listOf("ss", "sh", "ch", "x", "z")

/**
 * Основа без окончания и с «-e». Основа «согласная-гласная-согласная» (hop, mak, us) с короткой
 * гласной удвоила бы согласную (hopped), поэтому без удвоения это почти всегда основа на -e.
 */
private fun stemOrStemE(stem: String, preferE: Boolean = false): List<String> =
    if (preferE || endsShortSyllable(stem)) listOf(stem + "e", stem) else listOf(stem, stem + "e")

private fun endsShortSyllable(stem: String): Boolean {
    if (stem.length < 2) return false
    val last = stem.last()
    val vowel = stem[stem.length - 2]
    if (last in VOWELS || last in "wxy" || vowel !in VOWELS) return false
    return stem.length == 2 || stem[stem.length - 3] !in VOWELS
}

private const val VOWELS = "aeiou"
