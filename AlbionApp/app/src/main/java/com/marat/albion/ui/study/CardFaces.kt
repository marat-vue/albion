package com.marat.albion.ui.study

import com.marat.albion.ui.theme.IpaStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.data.content.Deck
import com.marat.albion.data.content.FormVariant
import com.marat.albion.data.content.IrregularData
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.repo.WordCards
import com.marat.albion.data.library.BookWordsRepository
import com.marat.albion.ui.components.AudioButton
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.Hairline
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.components.folioRule

/** Озвучка, доступная лицевой и оборотной сторонам. */
interface CardAudio {
    val playingKey: String?
    fun play(deck: Deck, file: String?, text: String, key: String)
}

private fun deckOf(card: CardEntity) = Deck.byId(card.deck) ?: Deck.WORDS

/** Крупный термин на обеих сторонах; длинные выражения занимают несколько строк. */
private fun termStyle(term: String): TextStyle {
    val size = when {
        term.length <= 10 -> 64
        term.length <= 18 -> 52
        term.length <= 28 -> 42
        else -> 34
    }
    return TextStyle(fontFamily = com.marat.albion.ui.theme.AlbionFonts.Display, fontWeight = FontWeight.Medium,
        fontSize = size.sp, lineHeight = (size * 1.15f).sp, letterSpacing = (-size / 70f).sp,
        lineBreak = LineBreak.Heading, hyphens = Hyphens.None)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermHeader(card: CardEntity, audio: CardAudio, showIpa: Boolean = true) {
    val deck = deckOf(card)
    val key = "term:${card.id}"
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val style = termStyle(card.term)
            val measurer = rememberTextMeasurer()
            val longestWord = card.term.split(Regex("\\s+")).maxOfOrNull {
                measurer.measure(it, style, softWrap = false).size.width
            } ?: 0
            // Keep each word whole, including long terms on narrow screens and at large font scales.
            val scale = if (longestWord > constraints.maxWidth) constraints.maxWidth * .98f / longestWord else 1f
            Text(card.term, Modifier.fillMaxWidth(),
                style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale,
                    letterSpacing = style.letterSpacing * scale),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showIpa && card.ipa.isNotBlank()) {
                Text(card.ipa, style = IpaStyle, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(24.dp))
            }
            AudioButton(audio.playingKey == key, { audio.play(deck, card.audio, card.term, key) }, size = 48.dp)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WordCards.members(card).map { it.pos }.filter { it.isNotBlank() }.distinct().forEach { Chip(it) }
            LevelBadge(card.level)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardFront(card: CardEntity, irregular: IrregularData?, audio: CardAudio, showIpa: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.marat.albion.ui.components.SignalLabel("ВСПОМНИТЕ ЗНАЧЕНИЕ")
        Spacer(Modifier.width(72.dp).height(28.dp).folioRule(MaterialTheme.colorScheme.secondary.copy(alpha = .65f)))
        TermHeader(card, audio, showIpa = showIpa)
        if (card.senses.size > 1) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("Все значения · ${card.senses.size}", color = MaterialTheme.colorScheme.secondary)
        }
        if (irregular != null) {
            Spacer(Modifier.height(36.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                irregular.forms.drop(1).forEachIndexed { i, form ->
                    Column(
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("V${i + 2}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text("?", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Eyebrow(form.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Оборот. Секции проявляются каскадом сразу после переворота. */
@Composable
fun CardBack(
    card: CardEntity,
    irregular: IrregularData?,
    audio: CardAudio,
    modifier: Modifier = Modifier,
    showRussian: Boolean = true,
) {
    var i = 0
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp)) {
        TermHeader(card, audio)

        if (irregular != null) {
            Spacer(Modifier.height(22.dp))
            IrregularForms(card, irregular, audio, Modifier)
        }

        Spacer(Modifier.height(18.dp))

        Hairline(Modifier)
        Spacer(Modifier.height(16.dp))

        val senses = WordCards.members(card)
        if (senses.size > 1) {
            Text("Оцените знание всех ${senses.size} значений", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            com.marat.albion.ui.components.WordMeanings(card, showRussian)
            Spacer(Modifier.height(20.dp))
            com.marat.albion.ui.components.Disclosure("Определения и примеры · ${senses.size}") {
                senses.forEachIndexed { index, sense ->
                    if (index > 0) { Spacer(Modifier.height(20.dp)); Hairline(); Spacer(Modifier.height(16.dp)) }
                    // Внутри раскрытого списка примеры сразу видны — без второго уровня раскрытия.
                    SenseContent(sense, index, senses.size, audio, showRussian, irregular == null, header = card, inlineExamples = true)
                }
            }
        } else SenseContent(senses.first(), 0, 1, audio, showRussian, irregular == null)

        if (irregular != null) {
            irregular.forms.forEachIndexed { fi, form ->
                if (form.examples.isEmpty()) return@forEachIndexed
                Column(Modifier) {
                    Spacer(Modifier.height(20.dp))
                    Eyebrow("Examples · V${fi + 1} ${form.label}")
                    Spacer(Modifier.height(8.dp))
                    form.examples.forEach { ExampleLine(it, form.variants.map(FormVariant::text) + card.term) }
                }
            }
            if (irregular.note.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(14.dp),
                ) {
                    Eyebrow("Примечание")
                    Spacer(Modifier.height(6.dp))
                    Text(irregular.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SenseContent(
    sense: CardEntity, index: Int, total: Int, audio: CardAudio,
    showRussian: Boolean, showExamples: Boolean, header: CardEntity? = null, inlineExamples: Boolean = false,
) {
    if (total > 1) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("%02d".format(index + 1), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(sense.pos, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sense.ipa.isNotBlank() && sense.ipa != header?.ipa) Text(sense.ipa, style = IpaStyle)
            }
            if (sense.audio != null && sense.audio != header?.audio) {
                val key = "sense:${sense.id}"
                AudioButton(audio.playingKey == key,
                    { audio.play(deckOf(sense), sense.audio, sense.term, key) }, size = 48.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (sense.meaningEn.isNotBlank()) {
        if (total == 1) Eyebrow("Meaning")
        Spacer(Modifier.height(6.dp))
        Text(sense.meaningEn, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    if (showRussian && sense.meaningRu.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        if (total == 1) Eyebrow("Значение")
        Text(sense.meaningRu, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showExamples) {
        val examples = listOf(sense.example1, sense.example2).filter { it.isNotBlank() }.distinct()
        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            if (inlineExamples) {
                Eyebrow("Examples")
                Spacer(Modifier.height(4.dp))
                examples.forEach { ExampleLine(it, listOf(sense.term)) }
            } else com.marat.albion.ui.components.Disclosure("Примеры · ${examples.size}") {
                examples.forEach { ExampleLine(it, listOf(sense.term)) }
            }
        }
    }
    val source = remember(sense.id, sense.extra) { BookWordsRepository.source(sense) }
    if (source != null) {
        Spacer(Modifier.height(14.dp))
        Eyebrow("Из книги")
        Text(listOf(source.title, source.chapter).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IrregularForms(card: CardEntity, data: IrregularData, audio: CardAudio, modifier: Modifier) {
    val deck = deckOf(card)
    Column(modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)) {
        data.forms.forEachIndexed { index, form ->
            if (index > 0) Hairline()
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("V${index + 1}", style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                    Text(form.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.variants.forEach { v ->
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column {
                                Text(v.text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                                if (v.ipa.isNotBlank()) Text(v.ipa, style = IpaStyle)
                            }
                            v.audio.ifEmpty { listOf(null) }.forEachIndexed { k, file ->
                                val key = "form:${card.id}:${form.label}:${v.text}:$k"
                                AudioButton(
                                    playing = audio.playingKey == key,
                                    onClick = { audio.play(deck, file, v.text, key) },
                                    size = 48.dp,
                                    primary = k == 0,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExampleLine(text: String, highlight: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text("—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(highlightTerms(text, highlight), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/**
 * Выделяет в примере изучаемое выражение. Сначала ищем фразу целиком,
 * затем — отдельные слова по основе (account for → accounts … for).
 */
fun highlightTerms(text: String, terms: List<String>): AnnotatedString {
    val ranges = mutableListOf<IntRange>()
    for (term in terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
        val phrase = Regex("(?<![\\p{L}])" + Regex.escape(term) + "(?![\\p{L}])", RegexOption.IGNORE_CASE)
        val found = phrase.findAll(text).map { it.range }.toList()
        if (found.isNotEmpty()) { ranges += found; continue }
        term.split(' ', '-').filter { it.length >= 3 && it.lowercase() !in STOP }.forEach { token ->
            val stem = if (token.length > 4 && token.endsWith("e")) token.dropLast(1) else token
            Regex("(?<![\\p{L}])" + Regex.escape(stem) + "\\p{L}*", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { ranges += it.range }
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val merged = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, r ->
        val last = acc.lastOrNull()
        if (last != null && r.first <= last.last + 1) acc[acc.lastIndex] = last.first..maxOf(last.last, r.last) else acc += r
        acc
    }
    return buildAnnotatedString {
        var pos = 0
        for (r in merged) {
            append(text.substring(pos, r.first))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
            pos = r.last + 1
        }
        append(text.substring(pos))
    }
}

private val STOP = setOf("the", "and", "one", "someone", "something", "sth", "sb", "your", "you", "out", "off", "for", "with", "into")
