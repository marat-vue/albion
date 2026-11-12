package com.marat.albion

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import com.marat.albion.ui.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.ui.nav.AlbionNavigation
import com.marat.albion.data.content.ImportStatus
import com.marat.albion.ui.theme.AlbionTheme
import com.marat.albion.ui.theme.AlbionExperience
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Интерфейс адаптируется к повороту телефона, планшета и раскрытой раскладушки.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val container = (application as AlbionApp).container
        val initial = runBlocking { container.settings.current() }
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Экран загрузки — только при новом запуске и только пока готовятся материалы.
        val coldLaunch = savedInstanceState == null
        // Только для отладочной сборки: `adb shell am start -n com.marat.albion/.MainActivity --ei albion.holdLaunchMs 8000`
        // держит экран загрузки дольше, чтобы посмотреть петлю, — на эмуляторе материалы готовы за доли секунды.
        val holdMs = if (debuggable) intent.getIntExtra("albion.holdLaunchMs", 0).toLong() else 0L
        enableEdgeToEdge()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initial)
            val importStatus by container.importer.status.collectAsStateWithLifecycle()
            var launching by rememberSaveable {
                mutableStateOf(coldLaunch && (holdMs > 0 || importStatus is ImportStatus.Checking || importStatus is ImportStatus.Importing))
            }
            var held by remember { mutableStateOf(holdMs > 0) }
            LaunchedEffect(Unit) { if (held) { kotlinx.coroutines.delay(holdMs); held = false } }
            CompositionLocalProvider(LocalAppContainer provides container) {
                val dark = isAppInDarkTheme(settings.themeMode)
                // Иконки статус-бара и навигации следуют теме приложения; на зелёном переплёте загрузки — светлые.
                LaunchedEffect(dark, launching) {
                    val style = if (dark || launching) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                AlbionTheme(darkTheme = dark) {
                    AlbionExperience(settings) {
                        // Приложение собирается под экраном загрузки и к его уходу уже готово.
                        Box(Modifier.fillMaxSize()) {
                            AlbionNavigation()
                            if (launching) com.marat.albion.ui.launch.LaunchScreen(
                                ready = !held && importStatus !is ImportStatus.Checking && importStatus !is ImportStatus.Importing,
                                status = (importStatus as? ImportStatus.Importing)?.let { "Готовим материалы · ${it.index}/${it.total}" }
                                    ?: if (importStatus is ImportStatus.Checking) "ПРОВЕРЯЕМ МАТЕРИАЛЫ" else null,
                                onFinished = { launching = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as AlbionApp).container.audio.stop()
    }

}
