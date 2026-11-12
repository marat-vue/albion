package com.marat.albion.ui.nav

import com.marat.albion.ui.components.IconButton
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import com.marat.albion.ui.theme.Capsule
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import com.marat.albion.ui.components.PaneBottomSheet as ModalBottomSheet
import com.marat.albion.ui.theme.selectedContent
import com.marat.albion.ui.components.selectionSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.AlbionMark
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.Springs
import com.marat.albion.ui.theme.quietFloatAsState

internal data class RailMetrics(val enabled: Boolean, val compact: Boolean, val width: Dp, val itemHeight: Dp)

/** The available height must accommodate all five labelled targets, including system bars. */
@Composable
internal fun navigationRailMetrics(width: Dp, height: Dp): RailMetrics {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = tabs.map { measurer.measure(it.label, MaterialTheme.typography.labelMedium, softWrap = false).size }
    val textWidth = with(density) { labels.maxOf { it.width }.toDp() }
    val textHeight = with(density) { labels.maxOf { it.height }.toDp() }
    // NavigationWindow already supplies the safe, unobstructed height.
    val available = height
    val compactHeight = maxOf(48.dp, textHeight + 16.dp)
    val regularHeight = maxOf(80.dp, textHeight + 54.dp)
    val compact = available < regularHeight * tabs.size + 92.dp
    return RailMetrics(width >= 600.dp && available >= compactHeight * tabs.size, compact,
        if (compact) maxOf(112.dp, textWidth + 66.dp) else maxOf(104.dp, textWidth + 34.dp),
        if (compact) compactHeight else regularHeight)
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, tint: Color, size: Dp = 22.dp) {
    val scale by quietFloatAsState(if (selected && !LocalReducedMotion.current) 1.025f else 1f, Springs.smooth(), "tabScale")
    Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = tint,
        modifier = Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale })
}

@Composable
internal fun SideRail(selected: TopLevel, metrics: RailMetrics, onSelect: (TopLevel) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxHeight().background(scheme.background)
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))) {
        Column(Modifier.width(metrics.width).fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()).padding(vertical = if (metrics.compact) 0.dp else 4.dp).selectableGroup()) {
            if (!metrics.compact) AlbionMark(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 24.dp))
            tabs.forEach { tab ->
                val active = tab.route == selected
                val tint = if (active) scheme.onSurface else scheme.onSurfaceVariant
                val target = Modifier.fillMaxWidth().heightIn(min = metrics.itemHeight)
                    .padding(horizontal = 8.dp, vertical = 2.dp).clip(MaterialTheme.shapes.medium)
                    .background(if (active) scheme.surfaceContainerHighest else scheme.background)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab.route) })
                if (metrics.compact) Row(target.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabIcon(tab, active, tint, 20.dp)
                    Text(tab.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = tint, softWrap = false)
                } else Column(target.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TabIcon(tab, active, tint)
                    Spacer(Modifier.height(8.dp))
                    Text(tab.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = tint, textAlign = TextAlign.Center, softWrap = false)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
    }
}

/** Вкладка: иконка в тональной капсуле-индикаторе, подпись под ней. */
@Composable
private fun BarItem(icon: @Composable (Color) -> Unit, label: String, active: Boolean, style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (active) scheme.onSurface else scheme.onSurfaceVariant
    val indicator by quietFloatAsState(if (active) 1f else 0f, Springs.bouncy(), "tabIndicator")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(width = 56.dp, height = 30.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = indicator; scaleX = .6f + .4f * indicator }
                .background(scheme.surfaceContainerHighest, Capsule))
            icon(tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, Modifier.padding(horizontal = 2.dp), style = if (active) style.copy(fontWeight = FontWeight.SemiBold) else style,
            color = tint, softWrap = false)
    }
}

/**
 * Полные вкладки, если помещаются; иначе две прямые и стабильная «Ещё».
 * Панель — матовое стекло ([haze]): контент прокручивается под ней; при «Меньше движения» — непрозрачная.
 */
@Composable
internal fun BottomBar(selected: TopLevel, haze: HazeState, modifier: Modifier = Modifier, onSelect: (TopLevel) -> Unit) {
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    // Стекло — тоже «живой» эффект (контент плывёт под панелью), поэтому «Меньше движения» делает её непрозрачной.
    val glass = !LocalReducedMotion.current
    var open by rememberSaveable { mutableStateOf(false) }
    val tint = scheme.background
    val blur = remember(tint) {
        HazeBlurStyle {
            blurRadius(24.dp)
            backgroundColor(tint)
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = .72f))))
            noiseFactor(0f)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()
        .then(if (glass) Modifier.hazeBlur(HazeInput.Sources(haze), blur) else Modifier.background(scheme.background))
        .drawBehind { drawLine(scheme.outlineVariant, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
        .windowInsetsPadding(WindowInsets.navigationBars)) {
        val outer = if (maxWidth < 360.dp) 4.dp else 8.dp
        val available = maxWidth - outer * 2
        fun labelWidth(label: String) = maxOf(56.dp,
            with(density) { measurer.measure(label, style.copy(fontWeight = FontWeight.SemiBold), softWrap = false).size.width.toDp() } + 4.dp)
        fun equalSlotsFit(labels: List<String>) = labels.maxOf(::labelWidth) <= available / labels.size
        val full = equalSlotsFit(tabs.map { it.label })
        val fast = tabs.filter { it.route == HomeRoute || it.route == GamesRoute }
        val fastFits = equalSlotsFit(fast.map { it.label } + "Ещё")
        val direct = if (full) tabs else if (fastFits) fast else tabs.take(1)
        val labels = direct.map { it.label } + if (full) emptyList() else listOf("Ещё")
        val labelHeight = with(density) { labels.maxOf { measurer.measure(it, style, softWrap = false).size.height }.toDp() }
        val barHeight = maxOf(64.dp, labelHeight + 46.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = outer).padding(top = 6.dp, bottom = 4.dp)
            .height(barHeight).selectableGroup()) {
            direct.forEach { tab ->
                val active = tab.route == selected
                BarItem({ TabIcon(tab, active, it) }, tab.label, active, style,
                    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.small)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab.route) }))
            }
            if (!full) {
                val active = direct.none { it.route == selected }
                val current = tabs.first { it.route == selected }
                BarItem({ Icon(AlbionIcons.Menu, null, Modifier.size(22.dp), tint = it) }, "Ещё", active, style,
                    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = "Все разделы") { open = true }
                        .semantics { if (active) stateDescription = "Открыт раздел: ${current.label}" })
            }
        }
    }
    if (open) NavigationMenu(selected, { open = false }, onSelect)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationMenu(selected: TopLevel, onDismiss: () -> Unit, onSelect: (TopLevel) -> Unit) {
    val density = LocalDensity.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Разделы", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(AlbionIcons.Close, "Закрыть разделы") }
                }
                Column(Modifier.selectableGroup()) {
                    tabs.forEach { tab ->
                        val active = tab.route == selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clip(MaterialTheme.shapes.small).selectionSurface(active, MaterialTheme.shapes.small)
                            .selectable(active, role = Role.Tab, onClick = { onDismiss(); onSelect(tab.route) })
                            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TabIcon(tab, active, if (active) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tab.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurface)
                            if (active) Icon(AlbionIcons.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.selectedContent)
                        }
                    }
                }
            }
        }
    }
}
