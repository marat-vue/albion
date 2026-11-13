package com.marat.albion.data.speech

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ModelState { Unknown, Missing, Downloading, Verifying, Importing, Ready, Failed, Unavailable }
data class ModelFile(val id: String, val title: String, val fileName: String, val bytes: Long,
                            val url: String, val sha256: String)

/** Everything that differs between model families; the download, verification and storage logic is shared. */
class ModelCatalog(
    /** SharedPreferences name; also keeps the state of earlier installs. */
    val key: String,
    val directory: String,
    val options: List<ModelFile>,
    /** Option for devices with at least 5.5 GB RAM, and for the rest. */
    val roomy: String,
    val compact: String,
    /** null: importing a local file is not offered. */
    val customTitle: String?,
    val purpose: String,
    val readySummary: String,
    val missingSummary: String,
    val unavailableSummary: String,
    val validate: (File) -> Unit,
    val requireRuntime: () -> Unit,
    val customFile: String = "imported-$key.bin",
    /** Partial download name prefix: `.<prefix>-<id>.download`. */
    val partPrefix: String = key,
)

data class ModelStatus(
    val phase: ModelState = ModelState.Unknown,
    val selected: String = "",
    val installed: Set<String> = emptySet(),
    val downloaded: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    val allowMobile: Boolean = false,
    val catalog: ModelCatalog,
) {
    val busy get() = phase in setOf(ModelState.Downloading, ModelState.Verifying, ModelState.Importing)
    val title get() = catalog.options.firstOrNull { it.id == selected }?.title ?: catalog.customTitle.orEmpty()
    val ready get() = phase == ModelState.Ready
    val summary get() = when (phase) {
        ModelState.Ready -> "$title · ${catalog.readySummary}"
        ModelState.Downloading -> message ?: "Загружается $title"
        ModelState.Verifying -> "Проверяю файл модели…"
        ModelState.Importing -> "Импортирую модель…"
        ModelState.Failed -> "Модель требует внимания"
        ModelState.Unavailable -> catalog.unavailableSummary
        else -> catalog.missingSummary
    }
}

/** Persistent system download, verified staging files and a single active model. No model in the APK. */
class ModelDownloads(private val context: Context, private val scope: CoroutineScope, private val catalog: ModelCatalog) {
    private companion object { const val MAX_CUSTOM_BYTES = 8L * 1024 * 1024 * 1024 }
    private val prefs = context.getSharedPreferences(catalog.key, Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val mutex = Mutex()
    private var watcher: Job? = null
    private var importing: Job? = null
    /**
     * Проверенные модели живут во внутренней памяти: на Android 8–9 внешнюю папку приложения может
     * переписать любое приложение с доступом к памяти, а файл модели читает нативный код.
     */
    private val directory get() = File(context.noBackupFilesDir, catalog.directory).apply { mkdirs() }
    /** DownloadManager пишет только во внешнюю память; там лежат лишь незавершённые загрузки. */
    private val downloads get() = context.getExternalFilesDir(catalog.directory)?.apply { mkdirs() }
    private val recommended: String by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        if (info.totalMem >= 5_500_000_000L) catalog.roomy else catalog.compact
    }
    private val selected get() = prefs.getString("selected", recommended).orEmpty()
    private val mobile get() = prefs.getBoolean("mobile", false)
    private val downloadId get() = prefs.getLong("downloadId", -1)
    private val _state = MutableStateFlow(ModelStatus(selected = selected, allowMobile = mobile, catalog = catalog))
    val state = _state.asStateFlow()
    private fun file(id: String) = File(directory, catalog.options.firstOrNull { it.id == id }?.fileName ?: catalog.customFile)
    private fun part(id: String) = File(downloads ?: directory, ".${catalog.partPrefix}-$id.download")
    private fun legacy(id: String) = downloads?.let { File(it, file(id).name) }
    private fun installed(id: String): Boolean {
        val f = file(id)
        val known = catalog.options.firstOrNull { it.id == id }
        return f.isFile && prefs.getLong("verifiedTime.$id", -1) == f.lastModified() &&
            prefs.getLong("verifiedSize.$id", -1) == f.length() && (known == null || f.length() == known.bytes)
    }
    fun activeModel(): Pair<File, String>? {
        val id = selected
        return if (installed(id)) file(id) to "$id:${prefs.getString("digest.$id", "")}" else null
    }
    private fun publish(message: String? = null, failed: Boolean = false) {
        val ids = (catalog.options.map { it.id } + "custom").filter(::installed).toSet()
        if (selected == "custom" && "custom" !in ids) prefs.edit().putString("selected", recommended).commit()
        _state.value = ModelStatus(if (selected in ids) ModelState.Ready else if (failed) ModelState.Failed else ModelState.Missing,
            selected, ids, message = message, allowMobile = mobile, catalog = catalog)
    }
    private fun markVerified(id: String, digest: String) {
        val f = file(id)
        prefs.edit().putLong("verifiedTime.$id", f.lastModified()).putLong("verifiedSize.$id", f.length())
            .putString("digest.$id", digest).commit()
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                catalog.requireRuntime()
                if (importing?.isActive != true) File(directory, ".import-${catalog.key}.part").delete()
                migrateLegacy()
                if (reconcile()) startWatcher()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.value = ModelStatus(ModelState.Unavailable, selected, message = error.message, allowMobile = mobile, catalog = catalog)
            }
        }
    }
    suspend fun select(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(id in catalog.options.map { it.id } || id == "custom" && installed(id))
            if (_state.value.busy) return@withLock
            prefs.edit().putString("selected", id).commit()
            publish()
        }
    }
    suspend fun setAllowMobile(allow: Boolean) = mutex.withLock {
        if (!_state.value.busy) {
            prefs.edit().putBoolean("mobile", allow).apply()
            _state.value = _state.value.copy(allowMobile = allow)
        }
    }
    suspend fun downloadSelected() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value.busy) return@withLock
            try {
                catalog.requireRuntime()
                val option = catalog.options.firstOrNull { it.id == selected } ?: error("Выберите модель для загрузки.")
                if (installed(option.id)) { publish(); return@withLock }
                checkNotNull(downloads) { "Внешняя память недоступна: загрузка модели невозможна." }
                // Загрузка и её проверенная копия во внутренней памяти какое-то время существуют вместе.
                val needed = option.bytes * 2 + 200_000_000L
                require(StatFs(directory.absolutePath).availableBytes > needed) {
                    "Недостаточно места. Освободите ${needed / 1_000_000} МБ для модели."
                }
                part(option.id).delete()
                val request = DownloadManager.Request(Uri.parse(option.url))
                    .setTitle("Albion · ${option.title}").setDescription(catalog.purpose)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(mobile).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, catalog.directory, part(option.id).name)
                val id = dm.enqueue(request)
                prefs.edit().putLong("downloadId", id).putString("downloadModel", option.id).commit()
                _state.value = _state.value.copy(phase = ModelState.Downloading, total = option.bytes, downloaded = 0, message = null)
                startWatcher()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { publish(error.message?.takeIf { it.any { c -> c in 'А'..'я' } }
                ?: "Не удалось начать загрузку. Проверьте сеть и свободное место.", failed = true) }
        }
    }
    private fun startWatcher() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    if (!mutex.withLock { reconcile() }) break
                    delay(1000)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutex.withLock { clearDownload(); publish("Не удалось проверить загрузку. Повторите попытку.", failed = true) } }
        }
    }
    /** Called under mutex, including when a download completed while the app was closed. */
    private suspend fun reconcile(): Boolean {
        if (downloadId < 0) { publish(); return false }
        val option = catalog.options.firstOrNull { it.id == prefs.getString("downloadModel", null) }
        if (option == null) { clearDownload(); publish(); return false }
        val snapshot = dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
        if (snapshot?.first in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)) {
            val waiting = when (snapshot?.third) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Ожидаю Wi-Fi"
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Ожидаю подключение к сети"
                DownloadManager.PAUSED_WAITING_TO_RETRY -> "Сеть прервалась · загрузка продолжится"
                else -> null
            }
            _state.value = _state.value.copy(phase = ModelState.Downloading, selected = option.id,
                downloaded = snapshot?.second ?: 0, total = option.bytes, message = waiting)
            return true
        }
        if (snapshot?.first == DownloadManager.STATUS_SUCCESSFUL) {
            _state.value = _state.value.copy(phase = ModelState.Verifying, downloaded = option.bytes, total = option.bytes, message = null)
            val staging = File(directory, ".verify-${option.id}.part")
            try {
                val source = part(option.id)
                require(source.length() == option.bytes) { "Файл модели загружен не полностью." }
                // Хэш считается по внутренней копии: внешний файл после проверки мог бы подменить кто-то другой.
                copyInto(source, staging, option.bytes)
                source.delete()
                val hash = digest(staging)
                require(hash == option.sha256) { "Файл модели повреждён. Повторите загрузку." }
                catalog.validate(staging)
                atomicReplace(staging, file(option.id))
                markVerified(option.id, hash)
                clearDownload()
                publish()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { clearDownload(); publish(error.message ?: "Не удалось проверить модель.", failed = true) }
            finally { staging.delete() }
        } else {
            clearDownload()
            publish("Загрузка не завершена. Проверьте сеть и свободное место, затем повторите попытку.", failed = true)
        }
        return false
    }
    private fun clearDownload() {
        if (downloadId >= 0) runCatching { dm.remove(downloadId) }
        catalog.options.forEach { part(it.id).delete() }
        prefs.edit().remove("downloadId").remove("downloadModel").commit()
    }
    suspend fun cancel() {
        watcher?.cancelAndJoin()
        importing?.cancelAndJoin()
        withContext(Dispatchers.IO) { mutex.withLock { clearDownload(); publish() } }
    }
    suspend fun importFrom(uri: Uri) = withContext(Dispatchers.IO) {
            mutex.withLock {
                if (_state.value.busy || catalog.customTitle == null) return@withLock
                importing = currentCoroutineContext().job
                val staging = File(directory, ".import-${catalog.key}.part")
                try {
                    _state.value = _state.value.copy(phase = ModelState.Importing, message = null)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copyStream(input, staging, MAX_CUSTOM_BYTES)
                    } ?: error("Не удалось открыть выбранный файл.")
                    catalog.validate(staging)
                    val hash = digest(staging)
                    atomicReplace(staging, file("custom"))
                    markVerified("custom", hash)
                    prefs.edit().putString("selected", "custom").commit()
                    publish()
                } catch (error: CancellationException) { publish(); throw error }
                catch (error: Exception) { publish(error.message?.takeIf { it.any { c -> c in 'А'..'я' } }
                    ?: "Не удалось импортировать модель. Проверьте файл и свободное место.", failed = true) }
                finally { importing = null; staging.delete() }
            }
    }
    suspend fun deleteSelected() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_state.value.busy) {
                try {
                    val target = file(selected)
                    check(!target.exists() || target.delete()) { "Не удалось удалить модель. Попробуйте ещё раз." }
                    prefs.edit().remove("verifiedTime.$selected").remove("verifiedSize.$selected").remove("digest.$selected").commit()
                    if (selected == "custom") prefs.edit().putString("selected", recommended).commit()
                    publish()
                } catch (_: Exception) { publish("Не удалось удалить модель. Попробуйте ещё раз.", failed = true) }
            }
        }
    }
    /** Переносит модели, проверенные прежними версиями во внешней папке, с повторной сверкой хэша. */
    private suspend fun migrateLegacy() {
        for (id in catalog.options.map { it.id } + "custom") {
            val old = legacy(id) ?: continue
            if (!old.isFile || installed(id)) continue
            val trusted = prefs.getLong("verifiedTime.$id", -1) == old.lastModified() && prefs.getLong("verifiedSize.$id", -1) == old.length()
            val expected = catalog.options.firstOrNull { it.id == id }?.sha256 ?: prefs.getString("digest.$id", null)
            if (!trusted || expected == null) { old.delete(); continue }
            val staging = File(directory, ".verify-$id.part")
            try {
                _state.value = _state.value.copy(phase = ModelState.Verifying, message = null)
                copyInto(old, staging, old.length())
                val hash = digest(staging)
                if (hash == expected) {
                    catalog.validate(staging)
                    atomicReplace(staging, file(id))
                    markVerified(id, hash)
                }
                old.delete()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Модель останется неустановленной — её можно скачать заново. */ }
            finally { staging.delete() }
        }
    }
    private suspend fun copyInto(source: File, target: File, limit: Long) = source.inputStream().use { copyStream(it, target, limit) }
    private suspend fun copyStream(input: java.io.InputStream, target: File, limit: Long) {
        target.outputStream().use { output ->
            val buffer = ByteArray(1024 * 1024)
            var written = 0L
            var checked = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                require(written <= limit) { "Файл модели слишком большой." }
                // Не заполняем память устройства до отказа: оставляем запас системе.
                if (written - checked >= 64L * 1024 * 1024) {
                    checked = written
                    require(StatFs(directory.absolutePath).availableBytes > 200_000_000L) {
                        "Недостаточно места для модели. Освободите память устройства."
                    }
                }
                output.write(buffer, 0, count)
            }
        }
    }
    private fun atomicReplace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
