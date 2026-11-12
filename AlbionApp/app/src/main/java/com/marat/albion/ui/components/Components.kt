package com.marat.albion.ui.components

import androidx.compose.foundation.shape.CircleShape
import com.marat.albion.ui.theme.Capsule
import com.marat.albion.ui.theme.selectedBorder
import com.marat.albion.ui.theme.selectedContent
import com.marat.albion.ui.theme.selectedContainer
import com.marat.albion.ui.theme.controlBorder
import androidx.compose.foundation.layout.wrapContentHeight

import androidx.compose.animation.animateColorAsState
import com.marat.albion.ui.theme.quietDpAsState as animateDpAsState
import com.marat.albion.ui.theme.quietFloatAsState as animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.ui.theme.LocalFeedback
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.Springs
import com.marat.albion.ui.theme.appear
import com.marat.albion.ui.theme.pressScale

/** Мелкая подпись над заголовком. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, modifier = modifier, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .6.sp), color = color, maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Волосяная линия-разделитель. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    // Редакционный заголовок раздела: Literata без маркеров, счётчик — тихой подписью справа.
    SectionHeading(title, trailing, modifier, leadingGap = 0.dp, editorial = true) {}
}

/** Заголовок сохраняет системный размер шрифта; действия уступают ему полную строку при нехватке места. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, eyebrow: String? = null, includeStatusBarInsets: Boolean = true, compact: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Column(Modifier.then(if (includeStatusBarInsets) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
        .padding(top = if (compact) 18.dp else 24.dp, bottom = 20.dp)) {
        // Подпись над заголовком, действия — справа в одной строке с названием экрана.
        if (eyebrow != null) { SignalLabel(eyebrow, Modifier.appear(0)); Spacer(Modifier.height(10.dp)) }
        AdaptiveScreenTitle(title, trailing = trailing)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, Modifier.appear(2), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AdaptiveScreenTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Layout(content = {
        Text(title, Modifier.appear(1).semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        if (trailing != null) Box { trailing() }
    }, modifier = modifier.fillMaxWidth()) { children, constraints ->
        val actions = children.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = if (actions == null) 0 else 8.dp.roundToPx()
        val inline = actions != null && children[0].maxIntrinsicWidth(constraints.maxHeight) + actions.width + gap <= constraints.maxWidth
        val heading = children[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = if (inline) constraints.maxWidth - actions!!.width - gap else constraints.maxWidth))
        val height = if (inline) maxOf(heading.height, actions!!.height) else heading.height + (actions?.height ?: 0) + gap
        layout(constraints.maxWidth, height) {
            heading.placeRelative(0, if (inline) (height - heading.height) / 2 else 0)
            actions?.placeRelative(constraints.maxWidth - actions.width, if (inline) (height - actions.height) / 2 else heading.height + gap)
        }
    }
}

/** Круглая кнопка-иконка; [filled] — на тональной подложке без контура. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    spin: Float = 0f,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(48.dp).pressScale(interaction, 0.94f).clip(CircleShape)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button) { feedback.selection(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).background(if (filled) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun SettingsShortcut(onClick: () -> Unit) {
    RoundIconButton(AlbionIcons.Settings, "Настройки", onClick, filled = true)
}

/** Спокойная поверхность: тон слоя и контур 1 px, углы 12 dp, без тени. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    borderColor: Color = Color.Unspecified,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val edge = if (borderColor != Color.Unspecified) borderColor else if (onClick != null) MaterialTheme.colorScheme.controlBorder else MaterialTheme.colorScheme.outlineVariant
    val border = BorderStroke(1.dp, edge)
    if (onClick != null) {
        val feedback = LocalFeedback.current
        val interaction = remember { MutableInteractionSource() }
        Surface(onClick = { feedback.selection(); onClick() }, modifier = modifier.pressScale(interaction, 0.97f), shape = shape,
            color = color, border = border, interactionSource = interaction) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

enum class ButtonTone { Accent, Warm, Error }

@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    border: Color?,
    enabled: Boolean,
    contentPadding: PaddingValues,
    body: @Composable RowScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHigh, label = "pillBg")
    val fg by animateColorAsState(if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant, label = "pillFg")
    Surface(
        onClick = { feedback.selection(); onClick() }, enabled = enabled, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(interaction, 0.97f),
        shape = MaterialTheme.shapes.medium, color = bg, contentColor = fg,
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Row(Modifier.padding(contentPadding), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = body)
    }
}

/** Главное действие экрана: графитовая плашка 12 dp. [inverse] — для тёмных подложек. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Accent,
    inverse: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        inverse -> scheme.background to scheme.onBackground
        tone == ButtonTone.Error -> scheme.error to scheme.onError
        else -> scheme.primary to scheme.onPrimary
    }
    PillButton(onClick, modifier, container, content, null, enabled, PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge, textAlign = if (trailingIcon != null) TextAlign.Start else TextAlign.Center)
        if (trailingIcon != null) { Spacer(Modifier.width(10.dp)); Icon(trailingIcon, null, Modifier.size(20.dp)) }
    }
}

/** Второстепенное действие: прозрачная плашка с контуром 1 px. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    enabled: Boolean = true,
) {
    PillButton(onClick, modifier, Color.Transparent, MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.controlBorder, enabled, contentPadding) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** Озвучка: круглая кнопка; во время звучания — залитая, с расходящимся кольцом. */
@Composable
fun AudioButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, primary: Boolean = true) {
    val player = com.marat.albion.LocalAppContainer.current.audio
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (playing) scheme.primary else if (primary) scheme.surfaceContainerHigh else Color.Transparent, label = "audioBg")
    val fg by animateColorAsState(if (playing) scheme.onPrimary else scheme.onSurface, label = "audioFg")
    Box(
        modifier.size(size.coerceAtLeast(48.dp)).pressScale(interaction, 0.88f)
            .clickable(interaction, null, role = Role.Button) { if (playing) player.stop() else onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(bg, CircleShape)
            .then(if (primary || playing) Modifier else Modifier.border(1.dp, scheme.outline, CircleShape)), contentAlignment = Alignment.Center) {
            Icon(
                if (playing) AlbionIcons.Stop else AlbionIcons.VolumeUp,
                contentDescription = if (playing) "Остановить озвучку" else "Озвучить",
                tint = fg, modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

@Composable
fun LevelBadge(level: String?, modifier: Modifier = Modifier) {
    if (level.isNullOrBlank()) return
    Text(
        level.uppercase(),
        // Одна высота с [Chip]: рядом стоящие «article» и «A1» больше не прыгают по вертикали.
        modifier = modifier.heightIn(min = 28.dp).border(1.dp, MaterialTheme.colorScheme.outline, Capsule)
            .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.heightIn(min = 28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, Capsule)
            .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Прогресс: дорожка-капсула, заполнение догоняет значение на пружине. */
@Composable
fun ThinProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 3.dp,
) {
    val reduced = LocalReducedMotion.current
    val p by animateFloatAsState(progress.coerceIn(0f, 1f),
        if (reduced) androidx.compose.animation.core.snap() else Springs.smooth(), label = "progress")
    Canvas(modifier.fillMaxWidth().height(height).progressSemantics(progress.coerceIn(0f, 1f))) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * p
        if (w > 0f) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp).appear(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AlbionIcons.Inventory2, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Верхняя панель вложенных экранов: круглая «назад», заголовок Literata и действия. */
@Composable
fun TopBar(
    onBack: () -> Unit,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(AlbionIcons.ArrowBack, "Назад", onBack, filled = true)
        if (title != null) Text(
            title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        ) else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Слайдер: дорожка-капсула и круглый бегунок, который подрастает при перетаскивании. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val thumbWidth by animateDpAsState(if (dragged) 26.dp else 22.dp, Springs.snappy(), label = "thumb")
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
        modifier = modifier, colors = colors, interactionSource = interaction,
        thumb = { Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(thumbWidth).background(MaterialTheme.colorScheme.primary, CircleShape))
        } },
        track = { state ->
            SliderDefaults.Track(sliderState = state, colors = colors, modifier = Modifier.height(6.dp),
                drawStopIndicator = null, drawTick = { _, _ -> }, thumbTrackGapSize = 4.dp)
        },
    )
}

/**
 * Сегменты сохраняют кегль; если полные подписи не помещаются, выбор становится вертикальным.
 * [inverse] — для тёмных подложек (карточка дня).
 */
@Composable
fun SegmentedPicker(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    inverse: Boolean = false,
) {
    val feedback = LocalFeedback.current
    val scheme = MaterialTheme.colorScheme
    val track = if (inverse) scheme.onBackground.copy(alpha = 0.18f) else scheme.surfaceContainerHigh
    // Единый язык выбора: тонированный ирис с ирисовой рамкой (на акцентной плите — светлая плашка).
    val on = if (inverse) scheme.onBackground else scheme.selectedContent
    val off = if (inverse) scheme.background else scheme.onSurfaceVariant
    val reduced = LocalReducedMotion.current
    val shape = Capsule
    val style = MaterialTheme.typography.labelLarge
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (options.isEmpty()) return@BoxWithConstraints
        val labels = options.map { measurer.measure(it, style, softWrap = false, maxLines = 1) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fits = with(density) { labels.maxOf { it.size.width }.toDp() + 20.dp <= (maxWidth - 8.dp) / options.size }
        if (!fits) {
            Column(Modifier.fillMaxWidth().selectableGroup().background(track, MaterialTheme.shapes.medium).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { i, label ->
                    val active = i == selected
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                        .background(if (active) { if (inverse) scheme.background else scheme.selectedContainer } else Color.Transparent)
                        .then(if (active) Modifier.border(1.dp, if (inverse) scheme.background.copy(alpha = .3f) else scheme.selectedBorder, MaterialTheme.shapes.small) else Modifier)
                        .selectable(active, role = Role.RadioButton) { if (!active) { feedback.selection(); onSelect(i) } }
                        .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, Modifier.fillMaxWidth(), style = style, color = if (active) on else off, textAlign = TextAlign.Center)
                    }
                }
            }
            return@BoxWithConstraints
        }
        val height = with(density) { labels.maxOf { it.size.height }.toDp() + 24.dp }.coerceAtLeast(48.dp)
        BoxWithConstraints(Modifier.fillMaxWidth().height(height + 8.dp).background(track, shape).padding(4.dp)) {
            val segment = maxWidth / options.size.coerceAtLeast(1)
            val x by animateDpAsState(segment * selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)), if (reduced) androidx.compose.animation.core.snap() else Springs.snappy(), label = "segment")
            Box(Modifier.offset(x = x).width(segment).fillMaxHeight().background(if (inverse) scheme.background else scheme.selectedContainer, shape)
                .border(if (inverse) .75.dp else 1.dp, if (inverse) scheme.background.copy(alpha = .3f) else scheme.selectedBorder, shape))
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                options.forEachIndexed { i, label ->
                    val color by animateColorAsState(if (i == selected) on else off, label = "segmentText")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(shape)
                            .selectable(i == selected, role = Role.RadioButton) { if (i != selected) { feedback.selection(); onSelect(i) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, Modifier.fillMaxWidth().padding(horizontal = 10.dp), style = style, color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/** Вариант в вертикальном списке выбора: строка-карточка, круглая отметка заполняется на пружине. */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    val feedback = LocalFeedback.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) scheme.selectedContainer else scheme.surfaceContainerLow, label = "choiceBg")
    val fg by animateColorAsState(if (selected) scheme.selectedContent else scheme.onSurface, label = "choiceFg")
    val edge by animateColorAsState(if (selected) scheme.selectedBorder else scheme.controlBorder, label = "choiceEdge")
    val mark = fg
    val dot by animateFloatAsState(if (selected) 1f else 0f, Springs.bouncy(), label = "choiceDot")
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction, 0.98f)
            .clip(MaterialTheme.shapes.medium).background(bg)
            .border(1.dp, edge, MaterialTheme.shapes.medium)
            .selectable(selected, interaction, null, role = Role.RadioButton) { if (!selected) { feedback.selection(); onClick() } }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = if (selected) scheme.selectedContent else scheme.onSurfaceVariant)
        }
        Box(Modifier.size(22.dp).border(1.5.dp, if (selected) mark else scheme.controlBorder, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp * dot).background(mark, CircleShape))
        }
    }
}

/**
 * Подгонка декоративного логотипа. Информационный текст должен перестраиваться без уменьшения кегля.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    textAlign: TextAlign? = null,
) {
    BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val width = constraints.maxWidth
        val base = style.fontSize
        val density = androidx.compose.ui.platform.LocalDensity.current
        val size = remember(text, width, style, minSize, density) {
            if (!base.isSp || width == androidx.compose.ui.unit.Constraints.Infinity) return@remember base
            var s = base.value
            while (s > minSize.value && measurer.measure(text, style.copy(fontSize = s.sp), softWrap = false, maxLines = 1)
                    .size.width > width * 0.95f) s = (s - 0.5f).coerceAtLeast(minSize.value)
            s.sp
        }
        val ratio = if (base.isSp && base.value > 0f) size.value / base.value else 1f
        Text(text, style = style.copy(fontSize = size,
            lineHeight = if (style.lineHeight.isSp) (style.lineHeight.value * ratio).sp else style.lineHeight),
            color = color, maxLines = 1, softWrap = false, textAlign = textAlign,
            overflow = TextOverflow.Clip)
    }
}
