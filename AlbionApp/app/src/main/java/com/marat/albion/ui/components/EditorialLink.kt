package com.marat.albion.ui.components

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.theme.LocalFeedback
import com.marat.albion.ui.theme.pressScale

/** Строка-ссылка: номер или иконка в круге, заголовок, описание, прогресс и стрелка. */
@Composable
fun EditorialLink(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    index: String? = null,
    eyebrow: String? = null,
    progress: Float? = null,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).pressScale(interaction, 0.98f).clip(MaterialTheme.shapes.medium)
            .clickable(interaction, ripple(), role = Role.Button) { feedback.selection(); onClick() }
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null || index != null) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, Modifier.size(22.dp))
                else if (index != null) Text(index, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) { Eyebrow(eyebrow); Spacer(Modifier.height(2.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null && progress > 0f) {
                Spacer(Modifier.height(10.dp))
                ThinProgress(progress, height = 4.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(AlbionIcons.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
