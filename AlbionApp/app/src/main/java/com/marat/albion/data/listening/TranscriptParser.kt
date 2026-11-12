package com.marat.albion.data.listening

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Формат файла с текстом к записи. */
enum class TranscriptFormat { JSON, SRT, LRC }

/**
 * Разбор транскриптов: JSON выравнивания Albion `[{s,e,text,words:[{s,e,w}]}]` (мс),
 * а также SRT и LRC от пользователя — у них разметка только по фразам
 * (в «расширенном» LRC `<mm:ss.xx>` перед словами даёт и пословную).
 */
object TranscriptParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, format: TranscriptFormat = detect(text), durationMs: Long? = null): Transcript = when (format) {
        TranscriptFormat.JSON -> parseJson(text)
        TranscriptFormat.SRT -> parseSrt(text)
        TranscriptFormat.LRC -> parseLrc(text, durationMs)
    }

    /** По содержимому: JSON-массив, «-->» как в SRT, иначе LRC. */
    fun detect(text: String, fileName: String? = null): TranscriptFormat {
        when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "json" -> return TranscriptFormat.JSON
            "srt" -> return TranscriptFormat.SRT
            "lrc" -> return TranscriptFormat.LRC
        }
        val head = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            JSON_START.containsMatchIn(head) -> TranscriptFormat.JSON
            "-->" in text -> TranscriptFormat.SRT
            else -> TranscriptFormat.LRC
        }
    }

    fun parseJson(text: String): Transcript =
        clean(json.decodeFromString(ListSerializer(Segment.serializer()), text.trimStart('﻿')))

    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    // Android (ICU) не принимает неэкранированные } и ] вне класса символов, в отличие от JVM.
    private val TAG = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    fun parseSrt(text: String): Transcript {
        val segments = mutableListOf<Segment>()
        for (block in text.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿').split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
            val timeLine = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (timeLine < 0) continue
            val m = SRT_TIME.find(lines[timeLine]) ?: continue
            val g = m.groupValues
            val body = lines.drop(timeLine + 1).joinToString(" ") { it.replace(TAG, "") }.replace(Regex("\\s+"), " ").trim()
            segments += Segment(srtMs(g[1], g[2], g[3], g[4]), srtMs(g[5], g[6], g[7], g[8]), body)
        }
        return clean(segments)
    }

    private fun srtMs(h: String, m: String, s: String, frac: String): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').take(3).toLong()

    private val JSON_START = Regex("""^\[\s*(\{|\])""")
    private val LRC_OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\]""", RegexOption.IGNORE_CASE)
    private val LRC_STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val LRC_WORD = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** Конец последней строки LRC неизвестен: берём длительность записи или 5 секунд. */
    fun parseLrc(text: String, durationMs: Long? = null): Transcript {
        data class Line(val start: Long, val body: String)
        val lines = mutableListOf<Line>()
        var offset = 0L
        for (raw in text.trimStart('﻿').lines()) {
            val line = raw.trim()
            LRC_OFFSET.find(line)?.let { offset = it.groupValues[1].toLong() }
            val stamps = mutableListOf<Long>()
            var rest = line
            while (true) {
                val m = LRC_STAMP.matchAt(rest, 0) ?: break
                stamps += lrcMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue
            // Положительный offset в LRC сдвигает текст раньше.
            stamps.forEach { lines += Line((it - offset).coerceAtLeast(0), rest.trim()) }
        }
        lines.sortBy { it.start }
        val segments = lines.mapIndexedNotNull { i, line ->
            val end = lines.getOrNull(i + 1)?.start ?: durationMs?.takeIf { it > line.start } ?: (line.start + 5_000)
            lrcSegment(line.start, end, line.body)
        }
        return clean(segments)
    }

    /** Строка «расширенного» LRC: `<00:01.20>Hello <00:01.60>world`. */
    private fun lrcSegment(start: Long, end: Long, body: String): Segment? {
        val marks = LRC_WORD.findAll(body).toList()
        val plain = body.replace(LRC_WORD, "").replace(Regex("\\s+"), " ").trim()
        if (plain.isEmpty()) return null
        if (marks.isEmpty()) return Segment(start, end, plain)
        val words = marks.mapIndexedNotNull { k, m ->
            val from = m.range.last + 1
            val to = marks.getOrNull(k + 1)?.range?.first ?: body.length
            val w = body.substring(from, to).trim()
            if (w.isEmpty()) null
            else TimedWord(lrcMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]), 0, w)
        }
        val timed = words.mapIndexed { k, w -> w.copy(e = words.getOrNull(k + 1)?.s ?: end) }
        return Segment(start, end, plain, timed)
    }

    private fun lrcMs(m: String, s: String, frac: String): Long {
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return (m.toLong() * 60 + s.toLong()) * 1000 + f
    }

    /** Порядок по началу, без пустых фраз, конец не раньше начала; слова — внутри своей фразы и по порядку. */
    internal fun clean(raw: List<Segment>): Transcript = Transcript(
        raw.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && it.s >= 0 }
            .map { seg ->
                val e = maxOf(seg.e, seg.s)
                val words = seg.words.filter { it.w.isNotBlank() }
                    .map { w -> w.copy(s = w.s.coerceIn(seg.s, e), e = w.e.coerceIn(seg.s, e).coerceAtLeast(w.s.coerceIn(seg.s, e))) }
                    .sortedBy { it.s }
                seg.copy(e = e, words = words)
            }
            .sortedBy { it.s }
            .toList()
    )
}
