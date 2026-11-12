package com.marat.albion.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/** Спокойное движение: плавное проявление и мягкая остановка. */
object Easing {
    val Emphasized = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.16f, 0.6f, 0.3f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
}

/**
 * Пружины: служебные переходы затухают критически, а «физические» объекты —
 * карточки, бегунки, отметки — с лёгким отскоком (damping ≈ 0.8).
 * При «Меньше движения» всё это заменяется мгновенной сменой (см. quiet*-обёртки).
 */
object Springs {
    fun <T> snappy() = spring<T>(dampingRatio = 1f, stiffness = 420f)
    fun <T> smooth() = spring<T>(dampingRatio = 1f, stiffness = 200f)
    fun <T> bouncy() = spring<T>(dampingRatio = .8f, stiffness = 320f)
    /** Карточка: переворот, появление следующей, возврат после свайпа. */
    fun <T> card() = spring<T>(dampingRatio = .8f, stiffness = 260f)
    /** Бегунок переключателя. */
    fun <T> toggle() = spring<T>(dampingRatio = .75f, stiffness = 700f)
}

/** Removing the animation state also stops an in-flight transition when the setting changes. */
@Composable
fun quietFloatAsState(targetValue: Float, animationSpec: AnimationSpec<Float> = Springs.snappy(), label: String = "float"): State<Float> =
    if (LocalReducedMotion.current) rememberUpdatedState(targetValue)
    else animateFloatAsState(targetValue, animationSpec, label = label)

@Composable
fun quietDpAsState(targetValue: Dp, animationSpec: AnimationSpec<Dp> = Springs.snappy(), label: String = "dp"): State<Dp> =
    if (LocalReducedMotion.current) rememberUpdatedState(targetValue)
    else animateDpAsState(targetValue, animationSpec, label = label)

fun Modifier.quietContentSize(animationSpec: FiniteAnimationSpec<IntSize> = Springs.smooth()): Modifier = composed {
    if (LocalReducedMotion.current) this else animateContentSize(animationSpec)
}

@Composable
@Suppress("ModifierFactoryExtensionFunction") // animateItem requires the enclosing lazy scope.
fun LazyItemScope.quietItem(modifier: Modifier = Modifier): Modifier =
    if (LocalReducedMotion.current) modifier else modifier.animateItem()

@Composable
@Suppress("ModifierFactoryExtensionFunction") // animateItem requires the enclosing lazy grid scope.
fun LazyGridItemScope.quietItem(modifier: Modifier = Modifier): Modifier =
    if (LocalReducedMotion.current) modifier else modifier.animateItem()

/** Почти незаметное нажатие с плавным возвратом. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val isPressed by interaction.collectIsPressedAsState()
    val scale by quietFloatAsState(
        if (isPressed && !reduced) pressed.coerceAtLeast(.985f) else 1f,
        if (isPressed) spring(dampingRatio = 1f, stiffness = 1400f) else Springs.bouncy(),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Каскадное появление при первом показе: снизу вверх с растворением.
 * Элементы, появившиеся позже (при прокрутке), анимируются коротко, без задержки.
 */
fun Modifier.appear(index: Int = 0, step: Long = 45): Modifier = composed {
    val reduced = LocalReducedMotion.current
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(reduced) }
    val progress = remember { Animatable(if (shown) 1f else 0f) }
    val distance = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(reduced) {
        if (reduced) {
            progress.snapTo(1f)
            shown = true
        } else if (!shown) {
            delay((index * step).coerceAtMost(360))
            progress.animateTo(1f, tween(360, easing = Easing.Standard))
            shown = true
        }
    }
    graphicsLayer {
        val p = if (reduced) 1f else progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance
    }
}

/** Число, которое «катится»: каждая цифра въезжает снизу или сверху при изменении. */
@Composable
fun AnimatedNumber(value: Int, style: TextStyle, color: Color = Color.Unspecified, suffix: String = "") {
    val reduced = LocalReducedMotion.current
    if (reduced) { Text("$value$suffix", style = style, color = color); return }
    var previous by remember { mutableStateOf(value) }
    val up = value >= previous
    LaunchedEffect(value) { previous = value }
    androidx.compose.foundation.layout.Row {
        val text = value.toString()
        text.forEachIndexed { i, ch ->
            androidx.compose.animation.AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dir = if (up) 1 else -1
                    (androidx.compose.animation.slideInVertically(Springs.smooth()) { it * dir } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutVertically(Springs.smooth()) { -it * dir } + androidx.compose.animation.fadeOut())
                },
                label = "digit$i",
            ) { digit -> Text(digit.toString(), style = style, color = color) }
        }
        if (suffix.isNotEmpty()) Text(suffix, style = style, color = color)
    }
}

/** Central motion policy also covers expanding panels and changing exercise answers. */
@Composable
fun QuietAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: androidx.compose.animation.EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
    exit: androidx.compose.animation.ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    label: String = "visibility",
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    androidx.compose.animation.AnimatedVisibility(visible, modifier,
        enter = if (reduced) androidx.compose.animation.EnterTransition.None else enter,
        exit = if (reduced) androidx.compose.animation.ExitTransition.None else exit,
        label = label, content = content)
}

@Composable
fun <S> QuietAnimatedContent(
    targetState: S,
    modifier: Modifier = Modifier,
    transitionSpec: androidx.compose.animation.AnimatedContentTransitionScope<S>.() -> androidx.compose.animation.ContentTransform = {
        androidx.compose.animation.ContentTransform(androidx.compose.animation.fadeIn(), androidx.compose.animation.fadeOut())
    },
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    label: String = "content",
    contentKey: (S) -> Any? = { it },
    content: @Composable androidx.compose.animation.AnimatedContentScope.(S) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    androidx.compose.animation.AnimatedContent(targetState, modifier, transitionSpec = {
        if (reduced) androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None,
            androidx.compose.animation.ExitTransition.None, sizeTransform = null)
        else transitionSpec()
    }, contentAlignment = contentAlignment, label = label, contentKey = contentKey, content = content)
}

/** Общая сцена для shared element; null — переходы выключены («Меньше движения», складной экран). */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }

/**
 * Общий элемент между экранами (обложка → читалка, плитка урока → урок, слово → карточка).
 * Работает внутри NavDisplay: сцену анимации даёт LocalNavAnimatedContentScope.
 * Без сцены или при «Меньше движения» модификатор ничего не делает.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
fun Modifier.sharedElement(key: Any): Modifier = composed {
    // Сцену даёт только AlbionNavigation, поэтому вне NavDisplay (тесты, диалоги) локаль навигации не читаем.
    val scope = LocalSharedTransitionScope.current
    if (scope == null || LocalReducedMotion.current) return@composed this
    val animated = androidx.navigation3.ui.LocalNavAnimatedContentScope.current
    with(scope) {
        this@composed.sharedBounds(
            rememberSharedContentState(key), animated,
            boundsTransform = { _, _ -> Springs.card() },
            resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
