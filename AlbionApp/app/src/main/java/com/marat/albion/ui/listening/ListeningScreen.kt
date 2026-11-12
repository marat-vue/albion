package com.marat.albion.ui.listening

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.listening.ListenProgress
import com.marat.albion.data.listening.ListeningTrack
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.Chip
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.components.Disclosure
import com.marat.albion.ui.components.EmptyState
import com.marat.albion.ui.components.FieldError
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.LoadingState
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.components.RoundIconButton
import com.marat.albion.ui.components.SegmentedPicker
import com.marat.albion.ui.components.ThinProgress
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.nav.ListeningPlayerRoute
import com.marat.albion.ui.nav.Navigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Список треков «Аудио с текстом»: рассказы, песни и свои записи с субтитрами. */
@Composable
fun ListeningScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val tracks by c.listeningLibrary.tracks.collectAsStateWithLifecycle()
    val progress by remember { c.listeningLibrary.observeProgress() }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ListeningTrack?>(null) }
    // Импорт в два шага: сначала запись, потом текст к ней. Uri записи переживает поворот экрана.
    var pendingAudio by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(TrackFilter.ALL) }
    LaunchedEffect(Unit) { c.listeningLibrary.refresh() }

    val subtitlesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { subs ->
        val audio = pendingAudio?.let(Uri::parse)
        pendingAudio = null
        if (subs == null || audio == null) return@rememberLauncherForActivityResult
        importing = true; importError = null
        scope.launch {
            try {
                val track = c.listeningLibrary.import(audio, subs)
                navigator.open(ListeningPlayerRoute(track.id))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { importError = e.message ?: "Не удалось добавить запись" }
            finally { importing = false }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { audio ->
        if (audio != null) {
            pendingAudio = audio.toString()
            subtitlesPicker.launch(arrayOf("application/x-subrip", "text/plain", "application/json", "application/octet-stream", "*/*"))
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(navigator::back, "Аудио с текстом")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Рассказы и песни на английском. Текст подсвечивается вслед за голосом, " +
                        "касание слова открывает словарь, касание времени у фразы — переход к ней.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val all = tracks
            // Фильтр нужен, только когда в списке есть записи разных видов.
            val kinds = all.orEmpty().map(TrackFilter::of).toSet()
            if (kinds.size > 1) item(key = "filter") {
                val options = listOf(TrackFilter.ALL) + TrackFilter.entries.filter { it in kinds }
                SegmentedPicker(options.map { it.title }, options.indexOf(filter).coerceAtLeast(0), { filter = options[it] })
            }
            val list = all?.filter { filter == TrackFilter.ALL || TrackFilter.of(it) == filter }
            when {
                list == null -> item(key = "loading") { LoadingState("Загружаю записи") }
                list.isEmpty() -> item(key = "empty") {
                    EmptyState("Записей пока нет", "Встроенные треки появляются, если собрать приложение с материалами. Можно добавить свою запись или песню с текстом в LRC или SRT.")
                }
                else -> items(list, key = { it.id }) { track ->
                    TrackRow(track, progress[track.id],
                        onOpen = { navigator.open(ListeningPlayerRoute(track.id)) },
                        onDelete = if (track.bundled) null else ({ toDelete = track }))
                }
            }
            item(key = "import") {
                Column(Modifier.padding(top = 8.dp)) {
                    FieldError(importError)
                    GhostButton(if (importing) "Добавляю запись…" else "Добавить свою запись", {
                        importError = null
                        audioPicker.launch(arrayOf("audio/*"))
                    }, icon = AlbionIcons.Add, enabled = !importing)
                    Spacer(Modifier.height(10.dp))
                    Disclosure("Свои записи и песни", "Аудиофайл и текст в LRC или SRT", icon = AlbionIcons.HelpOutline) {
                        Text(
                            "Сначала выберите аудиофайл, затем файл с текстом: LRC (синхронизированный текст песни), SRT или JSON выравнивания Albion. " +
                                "Пословную подсветку для песни можно подготовить на компьютере скриптом scripts/listening/make_track.py. " +
                                "В SRT и LRC время указано для фраз, поэтому подсвечивается фраза целиком. " +
                                "Используйте записи, которые вам разрешено слушать: файлы остаются только на этом устройстве.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { track ->
        DestructiveConfirmationDialog(
            title = "Удалить запись?",
            message = "«${track.meta.title}» и текст к ней будут удалены с устройства. Отменить удаление нельзя.",
            onConfirm = {
                toDelete = null
                scope.launch {
                    try {
                        // Удаляемая запись может быть открыта в плеере — выгружаем её, место не сохраняем.
                        if (c.listening.state.value.trackId == track.id) c.listening.stop(saveProgress = false)
                        c.listeningLibrary.delete(track)
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { importError = "Не удалось удалить запись" }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun TrackRow(track: ListeningTrack, progress: ListenProgress?, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    val meta = track.meta
    Panel(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(meta.title, style = MaterialTheme.typography.titleMedium)
                val byline = listOfNotNull(meta.author.takeIf(String::isNotBlank), meta.reader.takeIf(String::isNotBlank)?.let { "читает $it" })
                if (byline.isNotEmpty()) Text(byline.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) RoundIconButton(AlbionIcons.Delete, "Удалить «${meta.title}»", onDelete)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (meta.level.isNotBlank()) Chip(meta.level)
            if (track.isSong) Chip("Песня")
            if (meta.accent.isNotBlank()) Chip(accentLabel(meta.accent))
            if (meta.durationMs > 0) Text(formatTime(meta.durationMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (track.bundled) meta.source.ifBlank { "Встроенная" } else "Своя запись", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val fraction = if (progress != null && meta.durationMs > 0) progress.positionMs.toFloat() / meta.durationMs else 0f
        if (fraction > 0.01f) {
            Spacer(Modifier.height(10.dp))
            ThinProgress(fraction)
        }
    }
}

/** Акцент показываем пометкой: полезно сравнивать британское и американское произношение. */
internal fun accentLabel(accent: String): String = when (accent.lowercase()) {
    "american" -> "Американский"
    "british" -> "Британский"
    "australian" -> "Австралийский"
    "irish" -> "Ирландский"
    "scottish" -> "Шотландский"
    "canadian" -> "Канадский"
    else -> accent
}

/** Фильтр списка; видов записей немного, поэтому — сегменты. */
internal enum class TrackFilter(val title: String) {
    ALL("Все"), STORIES("Рассказы"), SONGS("Песни"), OWN("Свои");

    companion object {
        fun of(track: ListeningTrack): TrackFilter = when {
            track.isSong -> SONGS
            !track.bundled -> OWN
            else -> STORIES
        }
    }
}
