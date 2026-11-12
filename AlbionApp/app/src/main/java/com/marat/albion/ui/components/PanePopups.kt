package com.marat.albion.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.marat.albion.ui.nav.LocalNavigationBounds
import com.marat.albion.ui.nav.LocalWindowFolds
import com.marat.albion.ui.nav.largestUnbrokenArea

/** Dialogs and popups have their own Android windows, so Activity padding alone cannot protect them. */
// FoldingFeature bounds use physical left/top coordinates, independent of layout direction.
@SuppressLint("RtlHardcoded")
@Composable
internal fun ConstrainPopupToPane() {
    val folds = LocalWindowFolds.current.orEmpty()
    val bounds = LocalNavigationBounds.current
    val pane = if (bounds.width <= 0 || bounds.height <= 0) null else largestUnbrokenArea(bounds, folds)
    val view = LocalView.current
    var parent: android.view.ViewParent? = view.parent
    var provider: DialogWindowProvider? = view as? DialogWindowProvider
    while (provider == null && parent != null) { provider = parent as? DialogWindowProvider; parent = parent.parent }
    val window = provider?.window
    val root = window?.decorView ?: view.rootView
    // Anchored menus also respect their owner pane's safe edges. Ordinary floating dialogs keep Material sizing.
    val region = pane?.takeIf { window == null || folds.isNotEmpty() }
    DisposableEffect(root, window, region) {
        if (region == null) onDispose {} else {
            val original = WindowManager.LayoutParams().apply {
                copyFrom(window?.attributes ?: (root.layoutParams as? WindowManager.LayoutParams ?: WindowManager.LayoutParams()))
            }
            fun applyBounds() {
                val current = window?.attributes ?: (root.layoutParams as? WindowManager.LayoutParams ?: return)
                val params = WindowManager.LayoutParams().apply { copyFrom(current) }
                if (window != null) {
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    params.x = region.left; params.y = region.top
                    params.width = region.width; params.height = region.height
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    params.dimAmount = .32f
                    if (Build.VERSION.SDK_INT >= 30) { params.setFitInsetsTypes(0); params.setFitInsetsSides(0) }
                    if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    val width = root.width.coerceAtMost(region.width)
                    val height = root.height.coerceAtMost(region.height)
                    if (root.width > region.width) params.width = region.width
                    if (root.height > region.height) params.height = region.height
                    params.x = params.x.coerceIn(region.left, (region.right - width).coerceAtLeast(region.left))
                    params.y = params.y.coerceIn(region.top, (region.bottom - height).coerceAtLeast(region.top))
                }
                if (current.x != params.x || current.y != params.y || current.width != params.width || current.height != params.height ||
                    current.gravity != params.gravity || current.flags != params.flags) {
                    if (window != null) window.attributes = params
                    else if (root.isAttachedToWindow) root.context.getSystemService(WindowManager::class.java).updateViewLayout(root, params)
                }
            }
            applyBounds()
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBounds() }
            root.addOnLayoutChangeListener(listener)
            onDispose {
                root.removeOnLayoutChangeListener(listener)
                if (root.isAttachedToWindow) {
                    if (window != null) window.attributes = original
                    else root.context.getSystemService(WindowManager::class.java).updateViewLayout(root, original)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val folded = LocalWindowFolds.current.orEmpty().isNotEmpty()
    // Let Material's rounded surface include the drag handle. An inset content frame
    // creates a second top edge and cuts through the first row of controls.
    ModalBottomSheet(onDismissRequest, modifier, sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = if (folded) Color.Transparent else BottomSheetDefaults.ScrimColor) {
        ConstrainPopupToPane()
        Column(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), content = content)
    }
}

@Composable
fun PaneAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(onDismissRequest, confirmButton = { ConstrainPopupToPane(); confirmButton() },
        modifier = modifier.then(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)),
        dismissButton = dismissButton, icon = icon, title = title, text = text, shape = shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
}

@Composable
fun PaneDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, onDismissRequest, modifier) { ConstrainPopupToPane(); content() }
}
