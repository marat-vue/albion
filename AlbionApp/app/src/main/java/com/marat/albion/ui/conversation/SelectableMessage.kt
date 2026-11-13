package com.marat.albion.ui.conversation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.marat.albion.ui.library.sentenceAt
import com.marat.albion.ui.library.wordAt

/**
 * Текст реплики с выделением как в читалке: касание — слово, долгое нажатие — предложение,
 * долгое нажатие с протягиванием — фрагмент. [onSelect] получает диапазон и признак «одно слово».
 */
@Composable
internal fun SelectableMessage(
    text: String,
    style: TextStyle,
    color: Color,
    selectionColor: Color,
    selected: IntRange?,
    onSelect: (IntRange, Boolean) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var dragRange by remember { mutableStateOf<IntRange?>(null) }
    val shown = dragRange ?: selected
    val annotated = remember(text, shown, selectionColor) {
        buildAnnotatedString {
            append(text)
            shown?.let { r ->
                val s = r.first.coerceIn(0, text.length)
                val e = (r.last + 1).coerceIn(s, text.length)
                addStyle(SpanStyle(background = selectionColor, textDecoration = TextDecoration.Underline), s, e)
            }
        }
    }
    Text(annotated, style = style, color = color, onTextLayout = { layout = it },
        modifier = Modifier
            .semantics {
                onClick("Перевести реплику") { onSelect(text.indices, false); true }
                customActions = listOf(CustomAccessibilityAction("Перевести первое слово") {
                    wordAt(text, 0)?.let { onSelect(it, true) }; true
                })
            }
            .pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var timedOut = false
                    val up = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    } catch (e: PointerEventTimeoutCancellationException) {
                        timedOut = true
                        null
                    }
                    val l = layout ?: return@awaitEachGesture
                    if (!timedOut) {
                        // Короткое касание — слово; null — жест забрала прокрутка ленты.
                        if (up != null) wordAt(text, l.getOffsetForPosition(up.position))?.let { onSelect(it, true) }
                        return@awaitEachGesture
                    }
                    // Долгое нажатие: предложение или протягивание по словам.
                    val anchorWord = wordAt(text, l.getOffsetForPosition(down.position))
                    dragRange = sentenceAt(text, l.getOffsetForPosition(down.position))
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved && anchorWord != null) {
                            val current = wordAt(text, l.getOffsetForPosition(change.position)) ?: continue
                            dragRange = minOf(anchorWord.first, current.first)..maxOf(anchorWord.last, current.last)
                        }
                    }
                    val result = dragRange
                    dragRange = null
                    if (result != null && text.substring(result.first, result.last + 1).isNotBlank()) {
                        onSelect(result, anchorWord != null && result == anchorWord)
                    }
                }
            })
}

/** Выделенный фрагмент реплики, для которого открыт перевод. */
internal data class MessageSelection(val messageId: Long, val range: IntRange, val isWord: Boolean)

internal fun MessageSelection.text(message: String) =
    message.substring(range.first.coerceIn(0, message.length), (range.last + 1).coerceIn(0, message.length)).trim()
