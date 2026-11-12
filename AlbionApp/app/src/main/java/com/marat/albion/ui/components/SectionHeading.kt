package com.marat.albion.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Shared geometry for numbered and marked sections; the title gets priority over the count. */
@Composable
internal fun SectionHeading(
    title: String,
    trailing: String?,
    modifier: Modifier,
    leadingGap: Dp,
    compact: Boolean = false,
    editorial: Boolean = false,
    leading: @Composable () -> Unit,
) {
    val titleStyle = if (editorial) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    val firstLineHeight = with(LocalDensity.current) { titleStyle.lineHeight.toPx().roundToInt() }
    Layout(content = {
        Box { leading() }
        Text(title, Modifier.semantics { heading() }, style = titleStyle)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }, modifier = modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 28.dp, bottom = if (compact) 8.dp else 14.dp)) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val marker = children[0].measure(loose)
        val titleStart = marker.width + leadingGap.roundToPx()
        val titleWidth = (constraints.maxWidth - titleStart).coerceAtLeast(0)
        val count = children.getOrNull(2)?.measure(loose.copy(maxWidth = titleWidth))
        val gap = 12.dp.roundToPx()
        val inline = count != null && children[1].maxIntrinsicWidth(constraints.maxHeight) + count.width + gap <= titleWidth
        val heading = children[1].measure(loose.copy(maxWidth = if (inline) titleWidth - count!!.width - gap else titleWidth))
        val rowHeight = maxOf(marker.height, heading.height, if (inline) count!!.height else 0)
        val height = rowHeight + if (count != null && !inline) 4.dp.roundToPx() + count.height else 0
        layout(constraints.maxWidth, height) {
            marker.placeRelative(0, ((minOf(heading.height, firstLineHeight) - marker.height) / 2).coerceAtLeast(0))
            heading.placeRelative(titleStart, 0)
            count?.placeRelative(if (inline) constraints.maxWidth - count.width else titleStart,
                if (inline) (rowHeight - count.height) / 2 else rowHeight + 4.dp.roundToPx())
        }
    }
}
