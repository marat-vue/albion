package com.marat.albion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import com.marat.albion.ui.theme.Capsule
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.Springs
import com.marat.albion.ui.theme.controlBorder
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.theme.selectedBorder
import com.marat.albion.ui.theme.selectedContainer

@Composable
fun Modifier.selectionSurface(selected: Boolean, shape: Shape = MaterialTheme.shapes.medium): Modifier =
    if (!selected) this else background(MaterialTheme.colorScheme.selectedContainer, shape)
        .border(1.dp, MaterialTheme.colorScheme.selectedBorder, shape)

/**
 * Строка списка, чья страница открыта рядом (учебник, карточки на планшете) или которая отмечена.
 * Плашка рисуется с отступом от краёв строки, поэтому соседние выбранные строки не склеиваются,
 * а вёрстка списка не сдвигается.
 */
@Composable
fun Modifier.selectedRow(selected: Boolean): Modifier {
    val marked = semantics { this.selected = selected }
    if (!selected) return marked
    val shape = MaterialTheme.shapes.medium
    val fill = MaterialTheme.colorScheme.selectedContainer
    val edge = MaterialTheme.colorScheme.selectedBorder
    return marked.drawBehind {
        val dx = SelectedRowInsetX.toPx()
        val dy = SelectedRowInsetY.toPx()
        val plaque = Size((size.width - 2 * dx).coerceAtLeast(0f), (size.height - 2 * dy).coerceAtLeast(0f))
        val outline = shape.createOutline(plaque, layoutDirection, this)
        translate(dx, dy) {
            drawOutline(outline, fill)
            drawOutline(outline, edge, style = Stroke(1.dp.toPx()))
        }
    }
}

private val SelectedRowInsetX = 12.dp
private val SelectedRowInsetY = 4.dp

/**
 * Переключатель: дорожка-капсула и круглый бегунок на пружине с лёгким отскоком.
 * Только визуальная часть — переключение и семантику задаёт строка-владелец (`toggleable`).
 */
@Composable
fun AlbionSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val position by animateFloatAsState(if (checked) 1f else 0f,
        if (reduced) snap() else Springs.toggle(), label = "switch")
    val track by animateColorAsState(if (checked) scheme.primary else scheme.surfaceContainerHighest,
        if (reduced) snap() else androidx.compose.animation.core.spring(), label = "switchTrack")
    val thumb by animateColorAsState(if (checked) scheme.onPrimary else scheme.surfaceContainerLowest,
        if (reduced) snap() else androidx.compose.animation.core.spring(), label = "switchThumb")
    val edge = if (checked) scheme.primary else scheme.controlBorder
    Box(modifier.size(width = 50.dp, height = 30.dp).alpha(if (enabled) 1f else .38f)
        .clip(Capsule).background(track).border(1.dp, edge, Capsule).padding(4.dp)) {
        Box(Modifier.offset(x = 20.dp * position).size(22.dp).background(thumb, CircleShape)
            .then(if (checked) Modifier else Modifier.border(1.dp, scheme.outlineVariant, CircleShape)))
    }
}
