package com.marat.albion.data.content

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.repo.WordCards
import com.marat.albion.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.zip.CRC32

sealed interface ImportStatus {
    data object Checking : ImportStatus
    data class Importing(val deck: Deck, val index: Int, val total: Int) : ImportStatus
    data object Ready : ImportStatus
    /** В сборке нет материалов (albion.bundleContent=false). */
    data object Empty : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

/**
 * Переносит таблицы из assets/content/collections в Room.
 * Импорт запускается, только если содержимое таблиц изменилось (CRC32) —
 * прогресс FSRS при этом сохраняется, потому что id карточек стабильны.
 */
class ContentImporter(
    private val assets: AssetManager,
    private val db: AlbionDatabase,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    private val parser = CollectionParser(json)
    private val _status = MutableStateFlow<ImportStatus>(ImportStatus.Checking)
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    /** Одновременные вызовы (старт приложения, повтор из настроек) идут по очереди, а не дублируют работу. */
    private val running = kotlinx.coroutines.sync.Mutex()

    suspend fun importIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) { running.withLock {
        try {
            _status.value = ImportStatus.Checking
            val available = Deck.bundled.filter { assetExists(COLLECTIONS, requireNotNull(it.file)) }
            val tasksBytes = if (assetExists(COLLECTIONS, GRAMMAR_TASKS)) {
                assets.open("$COLLECTIONS/$GRAMMAR_TASKS").use { it.readBytes() }
            } else null
            if (available.isEmpty() && tasksBytes == null) {
                val hasCards = db.cards().observeTotal().first() > 0
                _status.value = if (hasCards) ImportStatus.Ready else ImportStatus.Empty
                return@withContext
            }
            val bytes = available.associateWith { deck -> assets.open("$COLLECTIONS/${deck.file}").use { it.readBytes() } }
            // Список озвучки тоже входит в версию: новые mp3 без правки таблиц должны привязаться к карточкам.
            val audioFiles = available.associateWith { deck -> assets.list("$AUDIO/${deck.audioDir}")?.sorted().orEmpty() }
            val audioListing = audioFiles.values.flatten().joinToString("|").toByteArray()
            val version = computeVersion(bytes.values + listOfNotNull(tasksBytes) + audioListing)
            if (!force && settings.contentVersion() == version) {
                _status.value = ImportStatus.Ready
                return@withContext
            }
            available.forEachIndexed { i, deck ->
                _status.value = ImportStatus.Importing(deck, i + 1, available.size)
                val rows = XlsxReader.read(bytes.getValue(deck).inputStream())
                val audio = AudioIndex(audioFiles.getValue(deck))
                val cards = if (deck == Deck.IRREGULAR) parser.parseIrregular(rows, audio) else parser.parseCards(deck, rows, audio)
                save(deck, cards)
            }
            if (tasksBytes != null) saveTasks(parser.parseGrammarTasks(XlsxReader.read(tasksBytes.inputStream())))
            db.reviews().deleteOrphanStates()
            settings.setContentVersion(version)
            _status.value = ImportStatus.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            _status.value = ImportStatus.Failed(t.message ?: t.javaClass.simpleName)
        }
    } }

    private fun assetExists(dir: String, name: String) =
        runCatching { assets.list(dir)?.contains(name) == true }.getOrDefault(false)

    private fun computeVersion(files: Collection<ByteArray>): String {
        val crc = CRC32()
        files.forEach { crc.update(it) }
        return "$IMPORTER_VERSION:${crc.value}"
    }

    private suspend fun save(deck: Deck, cards: List<CardEntity>) {
        // Пустой разбор означает повреждённую таблицу: удалять карточки нельзя — вместе с ними исчез бы прогресс FSRS.
        check(cards.isNotEmpty()) { "Таблица колоды «${deck.title}» не прочиталась. Материалы не обновлены." }
        val newIds = cards.mapTo(HashSet()) { it.id }
        db.withTransaction {
            val existing = db.cards().idsOf(deck.id)
            // Переименованный термин (organize → organise): учебная история переходит на новый id до удаления старого.
            val moves = TermRenames.moves(deck, existing, newIds)
            moves.forEach { (old, new) -> db.termRenames().moveCard(old, new) }
            TermRenames.forDeck(deck).forEach { (old, new) ->
                if (newIds.any { it.startsWith("${deck.id}:$new#") }) {
                    db.termRenames().moveCollectionTerm(deck.id, WordCards.key(old), WordCards.key(new))
                }
            }
            val stale = existing.filterNot { it in newIds }
            stale.chunked(500).forEach { db.cards().delete(it) }
            cards.chunked(500).forEach { db.cards().upsert(it) }
        }
    }

    private suspend fun saveTasks(tasks: List<GrammarTaskEntity>) {
        check(tasks.isNotEmpty()) { "Таблица грамматических заданий не прочиталась. Материалы не обновлены." }
        val dao = db.grammarTasks()
        val newIds = tasks.mapTo(HashSet()) { it.id }
        db.withTransaction {
            dao.ids().filterNot { it in newIds }.chunked(500).forEach { dao.delete(it) }
            tasks.chunked(500).forEach { dao.upsert(it) }
        }
    }

    companion object {
        const val ROOT = "content"
        /** Грамматические задания для мини-игр. */
        const val GRAMMAR_TASKS = "grammar_tasks.xlsx"
        const val COLLECTIONS = "$ROOT/collections"
        const val AUDIO = "$ROOT/audio"
        const val GRAMMAR = "$ROOT/grammar"

        /** Увеличить при изменении логики разбора — тогда таблицы переимпортируются. */
        private const val IMPORTER_VERSION = 2

    }
}

/**
 * Разбор строк xlsx в карточки. Колонки ищутся по заголовкам, поэтому их
 * порядок в таблице можно менять; при отсутствии заголовка — позиция по умолчанию.
 */
class CollectionParser(private val json: Json) {

    private class Header(row: List<String>) {
        private val cells = row.map { it.trim().lowercase() }
        fun find(default: Int, predicate: (String) -> Boolean): Int =
            cells.indexOfFirst(predicate).takeIf { it >= 0 } ?: default
    }

    private fun List<String>.cell(i: Int) = getOrNull(i)?.trim().orEmpty()

    fun parseCards(deck: Deck, rows: List<List<String>>, audio: AudioIndex): List<CardEntity> {
        if (rows.isEmpty()) return emptyList()
        val h = Header(rows.first())
        val term = h.find(0) { true }
        val ipa = h.find(1) { "ipa" in it || "транскрип" in it }
        val pos = h.find(-1) { "часть речи" in it || "part of speech" in it }
        val en = h.find(if (pos >= 0) 3 else 2) { ("определение" in it || "значение" in it) && "англ" in it }
        val ru = h.find(if (pos >= 0) 4 else 3) { "русск" in it }
        val ex1 = h.find(if (pos >= 0) 5 else 4) { "пример 1" in it || "example 1" in it }
        val ex2 = h.find(if (pos >= 0) 6 else 5) { "пример 2" in it || "example 2" in it }
        val level = h.find(-1) { "cefr" in it || "уровень" in it }

        val seen = HashMap<String, Int>()
        return rows.drop(1).mapIndexedNotNull { index, r ->
            val t = r.cell(term)
            if (t.isEmpty()) return@mapIndexedNotNull null
            val n = seen.merge(t, 1, Int::plus)!! - 1
            val p = if (pos >= 0) r.cell(pos) else ""
            CardEntity(
                id = "${deck.id}:$t#$n",
                deck = deck.id,
                position = index,
                term = t,
                ipa = r.cell(ipa),
                pos = p.ifEmpty { deck.kind },
                level = if (level >= 0) r.cell(level).uppercase().ifEmpty { null } else null,
                meaningEn = r.cell(en),
                meaningRu = r.cell(ru),
                example1 = r.cell(ex1),
                example2 = r.cell(ex2),
                audio = audio.forTerm(t, p),
                extra = null,
            )
        }
    }

    fun parseIrregular(rows: List<List<String>>, audio: AudioIndex): List<CardEntity> {
        if (rows.isEmpty()) return emptyList()
        val h = Header(rows.first())
        fun ex(c: String) = "пример" in c
        val inf = h.find(0) { it.startsWith("инфинитив") && !ex(it) && "ipa" !in it }
        val infIpa = h.find(1) { "ipa" in it && "инфинитив" in it }
        val past = h.find(2) { it.startsWith("past simple") && !ex(it) }
        val pastIpa = h.find(3) { "ipa" in it && "past simple" in it }
        val pp = h.find(4) { it.startsWith("past participle") && !ex(it) }
        val ppIpa = h.find(5) { "ipa" in it && "participle" in it }
        val en = h.find(6) { "англ" in it && "значение" in it }
        val ru = h.find(7) { "русск" in it }
        val note = h.find(17) { "примечание" in it }
        val headerCells = rows.first().map { it.lowercase() }
        fun examplesFor(key: String, fallback: IntRange) =
            headerCells.indices.filter { key in headerCells[it] && ex(headerCells[it]) }.ifEmpty { fallback.toList() }
        val infEx = examplesFor("инфинитив", 8..10)
        val pastEx = examplesFor("past simple", 11..13)
        val ppEx = examplesFor("participle", 14..16)

        val seen = HashMap<String, Int>()
        return rows.drop(1).mapIndexedNotNull { index, r ->
            val t = r.cell(inf)
            if (t.isEmpty()) return@mapIndexedNotNull null
            val n = seen.merge(t, 1, Int::plus)!! - 1
            fun form(label: String, textCol: Int, ipaCol: Int, exCols: List<Int>, isPast: Boolean): VerbForm {
                val texts = r.cell(textCol).split("/").map { it.trim() }.filter { it.isNotEmpty() }
                val ipas = IPA_SEGMENT.findAll(r.cell(ipaCol)).map { it.value }.toList()
                val variants = texts.mapIndexed { i, text ->
                    FormVariant(
                        text = text,
                        ipa = if (ipas.size == texts.size) ipas[i] else ipas.joinToString(" "),
                        audio = audio.forVerbForm(text, isPast),
                    )
                }
                return VerbForm(label, variants, exCols.map { r.cell(it) }.filter { it.isNotEmpty() })
            }
            val forms = listOf(
                form("Infinitive", inf, infIpa, infEx, isPast = false),
                form("Past Simple", past, pastIpa, pastEx, isPast = true),
                form("Past Participle", pp, ppIpa, ppEx, isPast = true),
            )
            val data = IrregularData(forms, r.cell(note))
            val infinitive = forms[0].variants.firstOrNull()
            CardEntity(
                id = "${Deck.IRREGULAR.id}:$t#$n",
                deck = Deck.IRREGULAR.id,
                position = index,
                term = t,
                ipa = r.cell(infIpa),
                pos = "irregular verb",
                level = null,
                meaningEn = r.cell(en),
                meaningRu = r.cell(ru),
                example1 = forms[0].examples.getOrElse(0) { "" },
                example2 = forms[0].examples.getOrElse(1) { "" },
                audio = infinitive?.audio?.firstOrNull(),
                extra = json.encodeToString(IrregularData.serializer(), data),
            )
        }
    }

    /**
     * Грамматические задания: «№ · Уровень · Номер темы · Тема · Тип · Задание ·
     * Вариант 1–4 · Правильный ответ · Пояснение». Тип «ввод» — вписать пропуск,
     * остальное — выбор варианта. Колонки ищутся по заголовкам.
     */
    fun parseGrammarTasks(rows: List<List<String>>): List<GrammarTaskEntity> {
        if (rows.isEmpty()) return emptyList()
        val head = rows.first().map { it.trim().lowercase() }
        fun col(default: Int, predicate: (String) -> Boolean) = head.indexOfFirst(predicate).takeIf { it >= 0 } ?: default
        val num = col(0) { it == "№" || it == "id" || it == "номер" }
        val level = col(1) { it.startsWith("уровень") || it == "level" }
        val topicNo = col(2) { it.startsWith("номер темы") }
        val topicTitle = col(3) { it == "тема" || it == "topic" }
        val kind = col(4) { it.startsWith("тип") || it == "type" }
        val prompt = col(5) { it.startsWith("задание") || it.startsWith("предложение") }
        val options = head.indices.filter { head[it].startsWith("вариант") || head[it].startsWith("option") }.ifEmpty { listOf(6, 7, 8, 9) }
        val answer = col(10) { it.startsWith("правильн") || it == "answer" }
        val explanation = col(11) { it.startsWith("пояснени") || it == "explanation" }

        return rows.drop(1).mapIndexedNotNull { index, r ->
            val text = r.cell(prompt)
            val correct = r.cell(answer)
            if (text.isEmpty() || correct.isEmpty()) return@mapIndexedNotNull null
            val isInput = r.cell(kind).lowercase().let { it.startsWith("ввод") || it == "input" }
            val opts = options.map { r.cell(it) }.filter { it.isNotEmpty() }
            if (!isInput && opts.size < 2) return@mapIndexedNotNull null
            val id = r.cell(num).substringBefore('.').ifEmpty { "${index + 1}" }
            GrammarTaskEntity(
                id = "gt:$id",
                position = index,
                level = r.cell(level).uppercase().ifEmpty { "B1" },
                topic = r.cell(topicNo).substringBefore('.').toIntOrNull(),
                topicTitle = r.cell(topicTitle),
                kind = if (isInput) "input" else "choice",
                prompt = text,
                options = if (isInput) "" else opts.joinToString("\n"),
                answer = correct,
                explanation = r.cell(explanation),
            )
        }
    }

    private companion object {
        /** "/əˈbaɪdɪd/ / /əˈbəʊd/" → два сегмента; разделитель " / " сегментом не считается. */
        val IPA_SEGMENT = Regex("/(?!\\s)[^/]+(?<!\\s)/")
    }
}

/**
 * Сопоставление терминов и mp3-файлов. Имена файлов совпадают с терминами;
 * омонимы, которые на Windows не уживаются в одной папке, помечены
 * уточнением в скобках: "March (month)", "may (modal verb)", "read (past)".
 */
class AudioIndex(files: List<String>) {
    private val names: Set<String>
    private val byBase: Map<String, List<String>>

    init {
        val mp3 = files.filter { it.endsWith(".mp3", ignoreCase = true) }.map { it.dropLast(4) }
        names = mp3.toHashSet()
        byBase = mp3.groupBy { it.substringBefore(" (").trim().lowercase() }
    }

    fun forTerm(term: String, pos: String = ""): String? {
        if (term in names) return "$term.mp3"
        val candidates = byBase[term.lowercase()].orEmpty()
        if (candidates.isEmpty()) return null
        val sameCase = candidates.filter { it.substringBefore(" (") == term && it.contains(" (") }
        val pick = sameCase.firstOrNull { pos.isNotEmpty() && qualifier(it).contains(pos, ignoreCase = true) }
            ?: sameCase.firstOrNull()
            ?: candidates.firstOrNull { it.equals(term, ignoreCase = true) }
            ?: candidates.first()
        return "$pick.mp3"
    }

    fun forVerbForm(form: String, isPast: Boolean): List<String> {
        val candidates = byBase[form.lowercase()].orEmpty()
        if (candidates.isEmpty()) return emptyList()
        val pastOnly = candidates.filter { qualifier(it).equals("past", ignoreCase = true) }
        val primary = when {
            isPast && pastOnly.isNotEmpty() -> pastOnly
            else -> candidates.filter { !it.contains(" (") }
        }
        val alternatives = candidates.filter { it.contains(" (") && !qualifier(it).equals("past", ignoreCase = true) }
        return (primary + alternatives).distinct().map { "$it.mp3" }
    }

    private fun qualifier(name: String) = name.substringAfter(" (", "").removeSuffix(")")
}
