package com.marat.albion.ui.components

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.geometry.Offset

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.ui.theme.LocalReducedMotion
import com.marat.albion.ui.theme.Springs
import kotlinx.coroutines.launch

/**
 * Анимированная подсказка «на месте»: инверсная капсула с пульсирующей точкой появляется один раз
 * там, где действие неочевидно (жест, долгое нажатие), и исчезает по касанию.
 * [show] = false — подсказка ждёт своего момента (например, переворота карточки).
 */
@Composable
fun CoachMark(key: String, text: String, modifier: Modifier = Modifier, show: Boolean = true) {
    val c = LocalAppContainer.current
    val viewed by c.settings.viewedHelp.collectAsStateWithLifecycle(null)
    val visible = show && viewed != null && "coach.$key" !in viewed.orEmpty()
    val reduced = LocalReducedMotion.current
    androidx.compose.animation.AnimatedVisibility(
        visible, modifier,
        enter = if (reduced) fadeIn() else fadeIn() + expandVertically(Springs.smooth()),
        exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(Springs.smooth()),
    ) {
        val scheme = MaterialTheme.colorScheme
        val pulse = if (reduced) 0f else rememberInfiniteTransition(label = "coach")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "dot").value
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MaterialTheme.shapes.large)
                // Спокойная панель темы: в тёмной теме инверсная белая плашка слепила и спорила с карточкой.
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClickLabel = "Понятно") { c.appScope.launch { c.settings.markHelpViewed("coach.$key") } }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                val dot = scheme.primary
                Canvas(Modifier.size(22.dp)) {
                    val halo = size.minDimension * (0.4f + 0.6f * pulse)
                    drawCircle(dot.copy(alpha = 0.35f * (1f - pulse)), halo / 2)
                    drawCircle(dot, size.minDimension * 0.18f)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Icon(AlbionIcons.Close, "Скрыть подсказку", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        }
    }
}
