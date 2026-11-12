package com.marat.albion.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.marat.albion.R
import com.marat.albion.data.settings.Settings

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalFeedback = staticCompositionLocalOf { AlbionFeedback() }

/** View haptics honour the phone's accessibility and touch feedback preferences. */
class AlbionFeedback(
    private val view: View? = null,
    private val haptics: Boolean = false,
    private val onCompletion: () -> Unit = {},
) {
    fun selection() {
        if (haptics) view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    fun reveal() {
        if (haptics) view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    /** Верный ответ: короткое подтверждение. */
    fun correct() {
        if (haptics) view?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
    /** Ошибка: двойной «отказ» — заметно, но не тревожно. */
    fun wrong() {
        if (haptics) view?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
        )
    }
    fun complete() {
        if (haptics) view?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
        )
        onCompletion()
    }
}

@Composable
fun AlbionExperience(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pool = remember {
        SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
    }
    val sound = remember(pool) { pool.load(context, R.raw.albion_complete, 1) }
    DisposableEffect(pool) { onDispose { pool.release() } }
    val feedback = remember(view, settings.hapticsEnabled, settings.interfaceSounds, sound) {
        AlbionFeedback(view, settings.hapticsEnabled) {
            if (settings.interfaceSounds && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL && !audioManager.isMusicActive) {
                pool.play(sound, 0.18f, 0.18f, 1, 0, 1f)
            }
        }
    }
    fun readMotion() = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var systemReduced by remember(context) { mutableStateOf(readMotion()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemReduced = readMotion() }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(
        LocalFeedback provides feedback,
        LocalReducedMotion provides (settings.reduceMotion || systemReduced),
        content = content,
    )
}

/**
 * Отклик на ответы по счётчикам сессии: рост [correct] — подтверждение, рост [wrong] — отказ.
 * Первое значение (и возврат к нулю при новой попытке) не вибрирует.
 */
@Composable
fun AnswerHaptics(correct: Int, wrong: Int) {
    val feedback = LocalFeedback.current
    val last = remember { intArrayOf(correct, wrong) }
    androidx.compose.runtime.LaunchedEffect(correct, wrong) {
        if (correct > last[0]) feedback.correct() else if (wrong > last[1]) feedback.wrong()
        last[0] = correct; last[1] = wrong
    }
}
