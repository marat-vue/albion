package com.marat.albion.data.listening

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marat.albion.data.content.ContentImporter
import com.marat.albion.data.db.AlbionDatabase
import com.marat.albion.data.library.Dictionary
import com.marat.albion.data.library.WordMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Метаданные трека (`track.json` рядом с записью). Для встроенных треков источник и
 * лицензия обязательны — они показываются в плеере («LibriVox, public domain»).
 */
@Serializable
data class TrackMeta(
    val id: String,
    val title: String,
    val author: String = "",
    val reader: String = "",
    /** «British» / «American» — у неосновного акцента в списке появляется пометка. */
    val accent: String = "",
    val level: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val textSource: String = "",
    val license: String = "",
    val attribution: String = "",
    val durationMs: Long = 0,
    val audio: String = "audio.mp3",
    val transcript: String = "transcript.json",
)

/** Трек в списке: встроенный (assets) или добавленный пользователем (файлы приложения). */
data class ListeningTrack(val meta: TrackMeta, val bundled: Boolean, private val dir: String) {
    val id: String get() = meta.id

    /** Адрес записи для ExoPlayer: `asset:///…` или `file://…`. */
    val audioUri: Uri get() = if (bundled) Uri.parse("asset:///$dir/${meta.audio}") else Uri.fromFile(File(dir, meta.audio))

    internal val assetAudioPath: String get() = "$dir/${meta.audio}"
    internal val audioFile: File get() = File(dir, meta.audio)
    internal val transcriptPath: String get() = "$dir/${meta.transcript}"
}

/** Где остановились: позиция и когда слушали (для «Продолжить»). */
data class ListenProgress(val positionMs: Long, val updatedAt: Long)

private val Context.listeningStore: DataStore<Preferences> by preferencesDataStore(
    name = "albion_listening",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * «Аудио с текстом»: треки из assets/content/listening/<id>/ (вшиваются скриптом
 * scripts/listening и albion.bundleContent) и свои записи с SRT/LRC. Прогресс прослушивания —
 * в отдельном DataStore, без Room, чтобы не менять схему БД.
 */
class ListeningRepository(
    private val context: Context,
    private val db: AlbionDatabase,
    private val dictionary: Dictionary,
    private val json: Json,
) {
    private val userRoot = File(context.filesDir, "listening")
    private val _tracks = MutableStateFlow<List<ListeningTrack>?>(null)
    /** null — ещё не прочитаны. */
    val tracks: StateFlow<List<ListeningTrack>?> = _tracks.asStateFlow()

    suspend fun refresh(): List<ListeningTrack> = withContext(Dispatchers.IO) {
        val bundled = context.assets.list(ROOT).orEmpty().sorted().mapNotNull { id ->
            val dir = "$ROOT/$id"
            runCatching {
                context.assets.open("$dir/$META").bufferedReader().use { json.decodeFromString(TrackMeta.serializer(), it.readText()) }
            }.getOrNull()?.let { ListeningTrack(it.copy(id = id), bundled = true, dir) }
        }
        val own = userRoot.listFiles().orEmpty().filter(File::isDirectory).sortedByDescending(File::lastModified).mapNotNull { dir ->
            runCatching { json.decodeFromString(TrackMeta.serializer(), File(dir, META).readText()) }.getOrNull()
                ?.let { ListeningTrack(it.copy(id = dir.name), bundled = false, dir.absolutePath) }
        }
        (bundled + own).also { _tracks.value = it }
    }

    suspend fun track(id: String): ListeningTrack? = (_tracks.value ?: refresh()).firstOrNull { it.id == id }

    suspend fun transcript(track: ListeningTrack): Transcript = withContext(Dispatchers.IO) {
        val text = if (track.bundled) context.assets.open(track.transcriptPath).bufferedReader().use { it.readText() }
        else File(track.transcriptPath).readText()
        TranscriptParser.parse(text, TranscriptParser.detect(text, track.meta.transcript), track.meta.durationMs.takeIf { it > 0 })
    }

    /**
     * Своя запись: аудиофайл и субтитры SRT/LRC (или JSON выравнивания) копируются в память
     * приложения. Пустые или нечитаемые субтитры — ошибка с понятным текстом.
     */
    suspend fun import(audio: Uri, subtitles: Uri): ListeningTrack = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val audioName = displayName(audio) ?: "audio.mp3"
        val subsName = displayName(subtitles) ?: "transcript.srt"
        val subsBytes = resolver.openInputStream(subtitles)?.use { readLimited(it, MAX_SUBTITLES + 1) }
            ?: error("Не удалось открыть файл с текстом")
        require(subsBytes.size <= MAX_SUBTITLES) { "Файл с текстом слишком большой — нужен SRT или LRC до 5 МБ." }
        val subsText = subsBytes.toString(Charsets.UTF_8)
        val format = TranscriptParser.detect(subsText, subsName)
        val parsed = runCatching { TranscriptParser.parse(subsText, format) }.getOrNull()
        require(parsed != null && parsed.segments.isNotEmpty()) { "В файле с текстом не нашлось ни одной фразы с временем. Нужен SRT или LRC." }
        val id = "user-" + UUID.randomUUID().toString().take(8)
        val dir = File(userRoot, id).apply { mkdirs() }
        try {
            val audioFile = "audio." + (audioName.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "mp3")
            val transcriptFile = "transcript." + format.name.lowercase()
            resolver.openInputStream(audio)?.use { input -> File(dir, audioFile).outputStream().use { input.copyTo(it) } }
                ?: error("Не удалось открыть аудиофайл")
            File(dir, transcriptFile).writeText(subsText)
            val meta = TrackMeta(
                id = id, title = audioName.substringBeforeLast('.').ifBlank { "Своя запись" },
                source = "Свой файл", durationMs = parsed.durationMs, audio = audioFile, transcript = transcriptFile,
            )
            File(dir, META).writeText(json.encodeToString(TrackMeta.serializer(), meta))
            ListeningTrack(meta, bundled = false, dir.absolutePath).also { refresh() }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    suspend fun delete(track: ListeningTrack) = withContext(Dispatchers.IO) {
        require(!track.bundled) { "Встроенные треки удалить нельзя" }
        File(userRoot, track.id).deleteRecursively()
        context.listeningStore.edit { it.remove(positionKey(track.id)); it.remove(updatedKey(track.id)) }
        refresh()
    }

    fun observeProgress(): Flow<Map<String, ListenProgress>> = context.listeningStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs.asMap().keys.mapNotNull { key -> key.name.removePrefix(POSITION).takeIf { key.name.startsWith(POSITION) } }
                .associateWith { id -> ListenProgress(prefs[positionKey(id)] ?: 0, prefs[updatedKey(id)] ?: 0) }
        }

    suspend fun saveProgress(id: String, positionMs: Long, now: Long = System.currentTimeMillis()) {
        context.listeningStore.edit { it[positionKey(id)] = positionMs.coerceAtLeast(0); it[updatedKey(id)] = now }
    }

    /** Слова из коллекций пользователя (с неправильными формами) — для cloze. */
    suspend fun collectionMarker(): WordMarker = withContext(Dispatchers.IO) {
        WordMarker(db.collections().words().map { it.term }.toSet(), dictionary.irregularForms())
    }

    /** Есть ли что-то в коллекциях — иначе cloze не из чего строить. */
    suspend fun hasCollectionWords(): Boolean = withContext(Dispatchers.IO) { db.collections().words().isNotEmpty() }

    /** Не больше [limit] байт: огромный «текст» не должен уронить приложение по памяти. */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    companion object {
        const val ROOT = "${ContentImporter.ROOT}/listening"
        private const val META = "track.json"
        private const val MAX_SUBTITLES = 5 * 1024 * 1024
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
        private const val POSITION = "position_"
        private fun positionKey(id: String) = longPreferencesKey(POSITION + id)
        private fun updatedKey(id: String) = longPreferencesKey("updated_$id")
    }
}
