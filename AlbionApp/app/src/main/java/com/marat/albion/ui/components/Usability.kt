package com.marat.albion.ui.components

import com.marat.albion.ui.components.QuietCircularProgressIndicator as CircularProgressIndicator
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip

/** Раскрывающийся блок: карточка, стрелка поворачивается, содержимое раскрывается на пружине. */
@Composable
fun Disclosure(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(start = 18.dp, end = 18.dp, bottom = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val reduced = com.marat.albion.ui.theme.LocalReducedMotion.current
    Column(modifier.fillMaxWidth().padding(vertical = 5.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
        .border(.75.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
        .clip(MaterialTheme.shapes.large)
        ) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
            .clickable(role = Role.Button) { expanded = !expanded }, contentAlignment = Alignment.CenterStart) {
            val titleStyle = MaterialTheme.typography.titleMedium
            val summaryStyle = MaterialTheme.typography.bodySmall
            val measurer = rememberTextMeasurer()
            val longest = maxOf(
                title.split(Regex("\\s+")).maxOfOrNull { measurer.measure(it, titleStyle, softWrap = false).size.width } ?: 0,
                summary.orEmpty().split(Regex("\\s+")).maxOfOrNull { measurer.measure(it, summaryStyle, softWrap = false).size.width } ?: 0,
            )
            val wordWidth = with(LocalDensity.current) { longest.toDp() + 2.dp }
            val inset = ((maxWidth - wordWidth) / 2).coerceIn(8.dp, 18.dp)
            val inline = maxWidth - inset * 2 >= wordWidth + 32.dp + if (icon == null) 0.dp else 54.dp
            val emblem: @Composable () -> Unit = {
                if (icon != null) Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
            }
            val labels: @Composable (Modifier) -> Unit = { labelModifier ->
                Column(labelModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface)
                    if (!summary.isNullOrBlank()) Text(summary, style = summaryStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (inline) Row(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { emblem(); Spacer(Modifier.width(14.dp)) }
                labels(Modifier.weight(1f))
                DisclosureIndicator(expanded, Modifier.padding(start = 12.dp))
            } else Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    emblem()
                    Spacer(Modifier.weight(1f))
                    DisclosureIndicator(expanded)
                }
                Spacer(Modifier.height(12.dp))
                labels(Modifier.fillMaxWidth())
            }
        }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = if (reduced) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.expandVertically(com.marat.albion.ui.theme.Springs.smooth()) + androidx.compose.animation.fadeIn(),
            exit = if (reduced) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.shrinkVertically(com.marat.albion.ui.theme.Springs.smooth()) + androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
        }
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp).semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FieldError(message: String?, modifier: Modifier = Modifier) {
    if (message != null) Text(message, modifier.fillMaxWidth().padding(top = 6.dp).semantics {
        liveRegion = LiveRegionMode.Polite; error(message)
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
fun AppSettingsButton() {
    val context = LocalContext.current
    TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")))
    }) { Text("Открыть настройки Albion", color = MaterialTheme.colorScheme.primary) }
}

@Composable
fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        RoundIconButton(AlbionIcons.Close, "Закрыть: $title", onClose)
    }
}

fun highlightedQuery(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val term = query.trim()
    if (term.isNotEmpty()) {
        var start = text.indexOf(term, ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, start + term.length)
            start = text.indexOf(term, start + term.length, ignoreCase = true)
        }
    }
}
