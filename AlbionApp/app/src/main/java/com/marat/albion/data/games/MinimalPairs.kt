package com.marat.albion.data.games

import com.marat.albion.data.speech.HeardWord
import com.marat.albion.data.speech.SpokenWords

/** Слово-участник пары: id карточки, написание и британская транскрипция. */
data class PairWord(val id: String, val term: String, val ipa: String)

/**
 * Звуковое противопоставление, трудное для русскоязычных. [a] и [b] — фонемы IPA,
 * [finalOnly] — различие важно только в конце слова (bad / bat: русские оглушают конечные звонкие).
 */
data class Contrast(val a: String, val b: String, val hint: String, val finalOnly: Boolean = false) {
    val label get() = "/$a/ – /$b/"
    fun matches(x: String, y: String) = (x == a && y == b) || (x == b && y == a)
}

/** Пара слов, различающихся одним звуком [contrast]. */
data class MinimalPair(val first: PairWord, val second: PairWord, val contrast: Contrast) {
    fun partnerOf(word: PairWord) = if (word.id == first.id) second else first
}

/** Что Whisper услышал в попытке произнести слово пары. */
enum class PairSpeech { TARGET, PARTNER, OTHER, NOTHING }

/**
 * Минимальные пары (ship / sheep, think / sink, west / vest) из транскрипций словаря.
 * Чистые функции: подбор пар, проверка распознанного слова.
 */
object MinimalPairs {
    /** Двухсимвольные фонемы британского IPA — читаются как одна. */
    private val MULTI = listOf("tʃ", "dʒ", "eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "eə", "ʊə", "iː", "uː", "ɑː", "ɔː", "ɜː")
    private val NOISE = Regex("[ˈˌ.()\\s/‿\\[\\]ʳ]")

    val CONTRASTS = listOf(
        Contrast("iː", "ɪ", "/iː/ — долгий, губы растянуты в улыбку; /ɪ/ — короткий и расслабленный, между «и» и «ы», согласный перед ним не смягчайте."),
        Contrast("uː", "ʊ", "/uː/ — долгий, губы округлены и вытянуты; /ʊ/ — короткий и расслабленный, не «ю»."),
        Contrast("æ", "e", "/æ/ — рот открыт широко, звук между «а» и «э»; /e/ — рот приоткрыт, как в «шесть»."),
        Contrast("æ", "ʌ", "/æ/ — широкий, ближе к «э»; /ʌ/ — короткое «а» без напряжения, как в безударном «пАра»."),
        Contrast("ʌ", "ɑː", "/ʌ/ — короткий и отрывистый; /ɑː/ — долгий и глубокий, рот открыт, как у врача: «а-а»."),
        Contrast("æ", "ɑː", "/æ/ — короткий и плоский, ближе к «э»; /ɑː/ — долгий и глубокий, без «р»."),
        Contrast("ʌ", "ɒ", "/ʌ/ — короткое «а», губы нейтральны; /ɒ/ — короткое открытое «о», губы чуть округлены."),
        Contrast("ɪ", "ʊ", "/ɪ/ — губы растянуты, ближе к «ы»; /ʊ/ — губы слегка округлены, ближе к «у»."),
        Contrast("ɒ", "ɔː", "/ɒ/ — короткое открытое «о»; /ɔː/ — долгое, губы округлены сильнее."),
        Contrast("ɜː", "ɔː", "/ɜː/ — губы не округлены, звук между «ё» и «э»; /ɔː/ — губы круглые, долгое «о»."),
        Contrast("əʊ", "ɔː", "/əʊ/ — скольжение «эу», губы движутся; /ɔː/ — ровное долгое «о» без скольжения."),
        Contrast("e", "eɪ", "/e/ — один ровный звук; /eɪ/ — скольжение «эй» к концу."),
        Contrast("e", "ɪ", "/e/ — рот приоткрыт; /ɪ/ — уже и короче, ближе к «ы»."),
        Contrast("θ", "s", "/θ/ — кончик языка между зубами, выдох; /s/ — язык за зубами. Не заменяйте /θ/ на «с»."),
        Contrast("θ", "f", "/θ/ — язык между зубами; /f/ — нижняя губа у верхних зубов."),
        Contrast("θ", "t", "/θ/ — язык между зубами, воздух идёт непрерывно; /t/ — смычка и короткий взрыв за зубами."),
        Contrast("ð", "z", "/ð/ — звонкий, язык между зубами; /z/ — язык за зубами. Не говорите «з»."),
        Contrast("ð", "d", "/ð/ — язык между зубами, звук тянется; /d/ — короткий удар за зубами."),
        Contrast("w", "v", "/w/ — губы округлены трубочкой, зубы губу не касаются; /v/ — верхние зубы на нижней губе."),
        Contrast("ŋ", "n", "/ŋ/ — задняя часть языка у нёба, как в «гонг» без «г»; /n/ — кончик языка за зубами."),
        Contrast("d", "t", "В конце слова /d/ звонкий, а гласный перед ним длиннее; /t/ — короткий и глухой. Не оглушайте.", finalOnly = true),
        Contrast("g", "k", "В конце слова /g/ остаётся звонким, гласный перед ним длиннее; /k/ — глухой.", finalOnly = true),
        Contrast("b", "p", "В конце слова /b/ звонкий, гласный перед ним длиннее; /p/ — глухой и короткий.", finalOnly = true),
        Contrast("z", "s", "В конце слова /z/ звонкий, гласный перед ним длиннее; /s/ — глухой.", finalOnly = true),
        Contrast("v", "f", "В конце слова /v/ звонкий, гласный перед ним длиннее; /f/ — глухой.", finalOnly = true),
        Contrast("dʒ", "tʃ", "В конце слова /dʒ/ звонкий, гласный перед ним длиннее; /tʃ/ — глухой.", finalOnly = true),
    )

    /**
     * Классика для русскоязычных: берутся, если оба слова есть в словаре, даже когда
     * транскрипции записаны по-разному и автоматический подбор их не сводит.
     */
    val CURATED = listOf(
        "ship" to "sheep", "live" to "leave", "fill" to "feel", "bit" to "beat", "sit" to "seat",
        "full" to "fool", "pull" to "pool", "look" to "Luke",
        "think" to "sink", "thank" to "sank", "thick" to "sick", "three" to "free", "thin" to "fin",
        "west" to "vest", "wine" to "vine", "wet" to "vet",
        "bad" to "bed", "man" to "men", "cap" to "cup", "cat" to "cut", "hat" to "hut",
        "bag" to "back", "dog" to "dock", "rise" to "rice", "eyes" to "ice", "prize" to "price",
        "sing" to "sin", "thing" to "thin", "then" to "den", "breathe" to "breeze",
        "walk" to "work", "port" to "pot", "cot" to "caught",
    )

    /** Транскрипция → фонемы. Берётся первый вариант («/ə; eɪ/» → ə), ударение и слоги отбрасываются. */
    fun phonemes(ipa: String): List<String> {
        val first = ipa.substringBefore('·').substringBefore(';').substringBefore(',')
        val s = first.replace(NOISE, "").replace(':', 'ː').replace('ɡ', 'g').replace('ɹ', 'r')
        val out = ArrayList<String>(s.length)
        var i = 0
        while (i < s.length) {
            val multi = MULTI.firstOrNull { s.startsWith(it, i) }
            if (multi != null) { out += multi; i += multi.length } else { out += s[i].toString(); i++ }
        }
        return out
    }

    /** Противопоставление, которым различаются слова, или null, если звуков больше одного или он не из списка. */
    fun contrastOf(a: List<String>, b: List<String>): Contrast? {
        if (a.size != b.size || a.isEmpty()) return null
        var at = -1
        for (i in a.indices) if (a[i] != b[i]) { if (at >= 0) return null; at = i }
        if (at < 0) return null
        val last = at == a.lastIndex
        return CONTRASTS.firstOrNull { it.matches(a[at], b[at]) && (!it.finalOnly || last) }
    }

    /**
     * Все пары среди [words]: по транскрипции и из [CURATED]. Однословные термины; слово с несколькими
     * значениями учитывается один раз. Пары, где слова пишутся одинаково или различаются только регистром, отбрасываются.
     */
    fun find(words: List<PairWord>): List<MinimalPair> {
        val unique = words.filter { it.term.isNotBlank() && it.term.none(Char::isWhitespace) && it.ipa.isNotBlank() }
            .distinctBy { it.term.lowercase() }
        val sounds = unique.associateWith { phonemes(it.ipa) }
        val result = LinkedHashMap<Set<String>, MinimalPair>()
        fun key(a: PairWord, b: PairWord) = setOf(a.term.lowercase(), b.term.lowercase())
        unique.groupBy { sounds.getValue(it).size }.values.forEach { group ->
            for (i in group.indices) for (j in i + 1 until group.size) {
                val a = group[i]
                val b = group[j]
                val contrast = contrastOf(sounds.getValue(a), sounds.getValue(b)) ?: continue
                result.getOrPut(key(a, b)) { MinimalPair(a, b, contrast) }
            }
        }
        val byTerm = unique.associateBy { it.term.lowercase() }
        for ((x, y) in CURATED) {
            val a = byTerm[x.lowercase()] ?: continue
            val b = byTerm[y.lowercase()] ?: continue
            if (key(a, b) in result) continue
            val contrast = curatedContrast(sounds.getValue(a), sounds.getValue(b)) ?: continue
            result[key(a, b)] = MinimalPair(a, b, contrast)
        }
        return result.values.toList()
    }

    /** Курируемая пара: ищем первый различающийся звук, допуская разную длину записи. */
    private fun curatedContrast(a: List<String>, b: List<String>): Contrast? {
        contrastOf(a, b)?.let { return it }
        val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: return null
        val x = a.getOrNull(i) ?: return null
        val y = b.getOrNull(i) ?: return null
        return CONTRASTS.firstOrNull { it.matches(x, y) }
    }

    /**
     * Пары на тренировку: слова идут в порядке [order] (ошибки первыми), каждое слово — не больше чем в одной паре,
     * одно противопоставление не повторяется подряд, если есть другие. Звучит [target] — случайная сторона пары.
     */
    fun session(pairs: List<MinimalPair>, order: List<String>, count: Int, random: kotlin.random.Random = kotlin.random.Random): List<PairTask> {
        if (count <= 0 || pairs.isEmpty()) return emptyList()
        val byWord = HashMap<String, MutableList<MinimalPair>>()
        pairs.forEach { p -> byWord.getOrPut(p.first.id) { mutableListOf() } += p; byWord.getOrPut(p.second.id) { mutableListOf() } += p }
        val used = HashSet<String>()
        val picked = mutableListOf<PairTask>()
        for (id in order) {
            if (picked.size >= count) break
            if (id in used) continue
            val pair = byWord[id].orEmpty().filter { it.first.id !in used && it.second.id !in used }.randomOrNull(random) ?: continue
            used += pair.first.id; used += pair.second.id
            // Ошибка на этом слове — его и проверяем; иначе звучит любая сторона.
            val own = if (pair.first.id == id) pair.first else pair.second
            picked += PairTask(pair, if (random.nextBoolean()) own else pair.partnerOf(own))
        }
        return interleave(picked)
    }

    /** Переставляет задания так, чтобы одно противопоставление не шло два раза подряд, когда это возможно. */
    fun interleave(tasks: List<PairTask>): List<PairTask> {
        val rest = tasks.toMutableList()
        val out = ArrayList<PairTask>(tasks.size)
        while (rest.isNotEmpty()) {
            val prev = out.lastOrNull()?.pair?.contrast
            val i = rest.indexOfFirst { it.pair.contrast != prev }.takeIf { it >= 0 } ?: 0
            out += rest.removeAt(i)
        }
        return out
    }

    /**
     * Что из пары узнал Whisper. Учитывается последнее осмысленное слово записи:
     * ученик часто начинает заново («ship… sheep»), засчитываем исправленную попытку.
     */
    fun judge(heard: List<HeardWord>, task: PairTask): PairSpeech {
        val keys = heard.map { SpokenWords.key(it.text) }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return PairSpeech.NOTHING
        val target = SpokenWords.key(task.target.term)
        val partner = SpokenWords.key(task.partner.term)
        val last = keys.lastOrNull { it == target || it == partner } ?: return PairSpeech.OTHER
        return if (last == target) PairSpeech.TARGET else PairSpeech.PARTNER
    }
}

/** Задание тренировки: какая пара и какое из слов звучит. */
data class PairTask(val pair: MinimalPair, val target: PairWord) {
    val partner get() = pair.partnerOf(target)
    /** Варианты на экране в постоянном порядке пары — место ответа не подсказывает. */
    val options get() = listOf(pair.first, pair.second)
}
