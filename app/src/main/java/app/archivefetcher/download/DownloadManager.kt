package app.archivefetcher.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.archivefetcher.R
import app.archivefetcher.Texts
import app.archivefetcher.data.AuthInterceptor
import app.archivefetcher.data.CountingInputStream
import app.archivefetcher.data.HttpStatusException
import app.archivefetcher.data.Md5
import app.archivefetcher.data.MultiDisc
import app.archivefetcher.data.RomNaming
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.archivefetcher.data.SafFiles
import app.archivefetcher.data.ZipExtractor
import app.archivefetcher.data.Prefs
import app.archivefetcher.data.awaitResponse
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Queue of downloads with a hard cap on parallel connections (Internet Archive throttles
 * aggressive clients), resume support via `.part` files, and back-off on 429/503 responses.
 */
class DownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val prefs: Prefs,
    private val texts: Texts,
    private val store: DownloadQueueStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow(store?.load().orEmpty())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val lock = Any()

    /** Two discs of a game can finish together; one playlist write at a time keeps the file whole. */
    private val playlistLock = Mutex()
    private val jobs = HashMap<String, Job>()
    private val calls = HashMap<String, Call>()

    // After every field above: restoring may start downloads, which uses them.
    init {
        if (store != null) {
            // At most one write every SAVE_DELAY_MS, always with the latest list, also while downloads report progress.
            @OptIn(FlowPreview::class)
            scope.launch { _tasks.sample(SAVE_DELAY_MS).collect { store.save(it) } }
        }
    }

    /**
     * Continues the downloads a restart interrupted, from their partial files. Called when the app's screen opens,
     * not when Android starts the process in the background, so the download service can start in the foreground.
     */
    fun resumeRestored() {
        if (_tasks.value.any { it.state == DownloadState.Queued }) pump()
    }

    /** [laterKey] links the task to its entry in the "download later" list, which is removed once the file is done. */
    fun enqueue(
        consoleName: String,
        folderUri: String,
        fileName: String,
        url: String,
        size: Long,
        md5: String? = null,
        laterKey: String? = null,
    ) {
        val id = taskId(folderUri, fileName)
        val existing = _tasks.value.firstOrNull { it.id == id }
        if (existing != null && existing.state != DownloadState.Failed && existing.state != DownloadState.Done) return
        val task = DownloadTask(id, consoleName, fileName, url, folderUri, size, md5, laterKey = laterKey)
        _tasks.update { list -> list.filterNot { it.id == id } + task }
        pump()
    }

    fun resume(id: String) {
        patch(id) { it.copy(state = DownloadState.Queued, message = null) }
        pump()
    }

    fun pause(id: String) {
        patch(id) { it.copy(state = DownloadState.Paused, bytesPerSec = 0, message = null) }
        stopWork(id)
        pump()
    }

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        _tasks.update { list -> list.filterNot { it.id == id } }
        stopWork(id)
        scope.launch {
            // Skipped when the same file was queued again meanwhile, so the new run keeps its partial file.
            if (_tasks.value.none { it.id == id }) runCatching { findPart(task)?.delete() }
        }
        pump()
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.isActive || it.state == DownloadState.Paused } }
    }

    private fun stopWork(id: String) {
        synchronized(lock) {
            // The job goes first so the run already sees it is no longer current when its call fails.
            jobs.remove(id)?.cancel()
            calls.remove(id)?.cancel()
        }
    }

    private fun pump() {
        synchronized(lock) {
            val running = _tasks.value.count { it.state == DownloadState.Running || it.state == DownloadState.Verifying || it.state == DownloadState.Extracting }
            var free = prefs.maxParallel - running
            for (task in _tasks.value) {
                if (free <= 0) break
                if (task.state != DownloadState.Queued || jobs.containsKey(task.id)) continue
                patch(task.id) { it.copy(state = DownloadState.Running, message = null) }
                // Registered before it starts, so the run can always tell whether it is still the current one.
                val job = scope.launch(start = CoroutineStart.LAZY) { execute(task.id) }
                jobs[task.id] = job
                job.start()
                free--
            }
        }
        if (_tasks.value.any { it.isActive }) startService()
    }

    private fun startService() {
        // Refused when the app is in the background without a foreground worker; the work then simply runs unprotected.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
    }

    private suspend fun execute(id: String) {
        val self = currentCoroutineContext()[Job]
        // A run that was paused, cancelled or already replaced by a newer one must not touch the task any more.
        fun isCurrent() = synchronized(lock) { jobs[id] === self }
        try {
            val task = _tasks.value.first { it.id == id }
            transfer(task, self)
            if (isCurrent() && shouldVerify(task)) verify(task, ::isCurrent)
            if (isCurrent() && shouldExtract(task)) extract(task, ::isCurrent)
            if (isCurrent()) {
                writePlaylists(task)
                patch(id) { it.copy(state = DownloadState.Done, doneBytes = it.totalBytes, bytesPerSec = 0, message = null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pausing cancels the live connection, which surfaces here as an IOException.
            if (isCurrent()) {
                patch(id) { it.copy(state = DownloadState.Failed, bytesPerSec = 0, message = describe(e)) }
            }
        } finally {
            synchronized(lock) {
                if (jobs[id] === self) {
                    jobs.remove(id)
                    calls.remove(id)
                }
            }
            pump()
        }
    }

    /**
     * After a disc of a multi-disc game arrives, writes `<game>.m3u` next to the discs so emulators show one entry.
     * The folder is read again each time, so the playlist grows as the other discs arrive. A failure here never fails
     * the download: the playlist is a convenience.
     */
    private suspend fun writePlaylists(task: DownloadTask) {
        if (!prefs.createPlaylists) return
        val game = MultiDisc.discOf(task.fileName)?.game ?: return
        playlistLock.withLock {
            runCatching {
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(task.folderUri)) ?: return@runCatching
                MultiDisc.playlists(SafFiles.childNames(context, dir))
                    .filter { RomNaming.stemOf(it.fileName) == RomNaming.safeFileName(game) }
                    .forEach { SafFiles.writeText(context, dir, it.fileName, it.content) }
            }
        }
    }

    private fun shouldVerify(task: DownloadTask) = prefs.verifyDownloads && !task.md5.isNullOrBlank()

    /** Hashes the finished file; a file that does not match the published checksum is deleted so it can be fetched again. */
    private suspend fun verify(task: DownloadTask, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(task.folderUri))
            ?: throw IOException(texts.get(R.string.err_folder_unreachable))
        val file = SafFiles.findChild(context, dir, task.fileName) ?: return
        patch(task.id) { it.copy(state = DownloadState.Verifying, extractProgress = 0f, bytesPerSec = 0, message = null) }
        val total = file.length().coerceAtLeast(1)
        val coroutineContext = currentCoroutineContext()
        var lastReport = 0L
        val actual = context.contentResolver.openInputStream(file.uri)?.use { stream ->
            Md5.hex(stream, checkCancelled = { coroutineContext.ensureActive() }) { read ->
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                    lastReport = now
                    patch(task.id) { it.copy(extractProgress = (read.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
        } ?: throw IOException(texts.get(R.string.err_write))
        if (!Md5.matches(task.md5.orEmpty(), actual)) {
            file.delete()
            throw IOException(texts.get(R.string.err_checksum))
        }
        patch(task.id) { it.copy(verified = true) }
    }

    private fun shouldExtract(task: DownloadTask) =
        prefs.extractZips && task.fileName.endsWith(".zip", ignoreCase = true)

    /** Unpacks the downloaded ZIP next to itself; on any failure the partial output is removed and the ZIP is kept. */
    private suspend fun extract(task: DownloadTask, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(task.folderUri))
            ?: throw IOException(texts.get(R.string.err_folder_unreachable))
        val zip = SafFiles.findChild(context, dir, task.fileName) ?: throw IOException(texts.get(R.string.err_unknown))
        patch(task.id) { it.copy(state = DownloadState.Extracting, extractProgress = 0f, bytesPerSec = 0, message = null) }

        val sink = SafExtractionSink(context, dir)
        val total = zip.length().coerceAtLeast(1)
        val coroutineContext = currentCoroutineContext()
        try {
            val extracted = context.contentResolver.openInputStream(zip.uri)?.use { raw ->
                val counting = CountingInputStream(raw)
                var lastReport = 0L
                ZipExtractor().extract(counting, sink, checkCancelled = { coroutineContext.ensureActive() }) {
                    val now = System.nanoTime()
                    if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                        lastReport = now
                        val fraction = (counting.count.toFloat() / total).coerceIn(0f, 1f)
                        patch(task.id) { it.copy(extractProgress = fraction) }
                    }
                }
            } ?: throw IOException(texts.get(R.string.err_write))
            if (extracted.isEmpty()) throw IOException(texts.get(R.string.err_extract_empty))
            sink.commit()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { sink.rollback() }
            throw e
        } catch (e: Exception) {
            sink.rollback()
            throw IOException(texts.get(R.string.err_extract, e.message ?: e::class.simpleName ?: "?"), e)
        }
        if (prefs.deleteZipAfterExtract) zip.delete()
    }

    /** Pauses every active download, used when the system forces the background service to stop. */
    fun pauseAll() {
        _tasks.value.filter { it.isActive }.forEach { pause(it.id) }
    }

    private suspend fun transfer(task: DownloadTask, self: Job?) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(task.folderUri))
            ?: throw IOException(texts.get(R.string.err_folder_unreachable))
        SafFiles.findChild(context, dir, task.fileName)?.let { existing ->
            if (task.totalBytes > 0 && existing.length() == task.totalBytes) return
        }
        val partName = task.fileName + PART_SUFFIX
        val part = SafFiles.findChild(context, dir, partName)
            ?: dir.createFile("application/octet-stream", partName)
            ?: throw IOException(texts.get(R.string.err_create_file))

        var networkRetries = 0
        while (true) {
            try {
                attemptDownload(task, dir, part, self)
                return
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                // Timeouts and dropped connections are retried (resuming from the partial file), so a long
                // unattended download survives a Wi-Fi hiccup. A paused or cancelled run is never retried.
                val current = synchronized(lock) { jobs[task.id] === self }
                if (!current || !isTransient(e) || ++networkRetries > MAX_NETWORK_RETRIES) throw e
                val wait = NETWORK_BACKOFF_SECONDS * networkRetries
                patch(task.id) { it.copy(message = texts.get(R.string.err_network_retry, wait.toInt())) }
                delay(wait * 1000)
                patch(task.id) { it.copy(message = null) }
            }
        }
    }

    /** Says which server answered and what was sent, so a refused login can be told apart from a missing one. */
    private fun loginRefusedMessage(code: Int, host: String): String {
        val methods = listOfNotNull(
            texts.get(R.string.auth_keys).takeIf { prefs.hasCredentials },
            texts.get(R.string.auth_account).takeIf { prefs.hasLogin },
        )
        return if (methods.isEmpty()) texts.get(R.string.err_login_required)
        else texts.get(R.string.err_login_refused, code, host, methods.joinToString(" + "))
    }

    private fun isTransient(e: IOException) =
        e is java.net.SocketTimeoutException || e is java.net.SocketException || e is java.io.EOFException ||
            e is javax.net.ssl.SSLException || e is java.net.UnknownHostException

    /** One pass of requests; throws on network problems and returns once the file is complete. */
    private suspend fun attemptDownload(task: DownloadTask, dir: DocumentFile, part: DocumentFile, self: Job?) {
        var attempt = 0
        while (true) {
            val offset = part.length()
            val request = Request.Builder().url(task.url).apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            val call = client.newCall(request)
            synchronized(lock) { if (jobs[task.id] === self) calls[task.id] = call }
            call.awaitResponse().use { response ->
                when {
                    response.code == 429 || response.code == 503 -> {
                        if (++attempt > MAX_RETRIES) {
                            throw HttpStatusException(response.code, texts.get(R.string.err_throttled))
                        }
                        val wait = response.header("Retry-After")?.toLongOrNull() ?: (BACKOFF_SECONDS shl (attempt - 1))
                        patch(task.id) { it.copy(message = texts.get(R.string.err_throttle_wait, wait.toInt())) }
                        delay(wait * 1000)
                        return@use
                    }
                    response.code == 401 || response.code == 403 ->
                        throw HttpStatusException(
                            response.code,
                            // Accounts exist only for the Internet Archive; other hosts just refused the file.
                            if (AuthInterceptor.isArchiveHost(response.request.url)) loginRefusedMessage(response.code, response.request.url.host)
                            else texts.get(R.string.err_http, response.code),
                        )
                    response.code == 416 -> {
                        // The range starts at or past the end: the partial file is most likely already complete.
                        if (task.totalBytes > 0 && offset >= task.totalBytes) {
                            finish(dir, part, task.fileName)
                            return
                        }
                        part.delete()
                        throw IOException(texts.get(R.string.err_partial_invalid))
                    }
                    !response.isSuccessful ->
                        throw HttpStatusException(response.code, texts.get(R.string.err_http, response.code))
                }
                val resumed = response.code == 206
                val body = response.body ?: throw IOException(texts.get(R.string.err_empty_response))
                val startAt = if (resumed) offset else 0L
                val mode = if (resumed) "wa" else "wt"
                val out = context.contentResolver.openOutputStream(part.uri, mode)
                    ?: throw IOException(texts.get(R.string.err_write))
                out.use { stream -> copy(task.id, body.byteStream(), stream, startAt) }
                finish(dir, part, task.fileName)
                return
            }
            patch(task.id) { it.copy(message = null) }
        }
    }

    private suspend fun copy(id: String, input: java.io.InputStream, output: java.io.OutputStream, startAt: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = startAt
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                currentCoroutineContext().ensureActive()
                output.write(buffer, 0, read)
                done += read
                windowBytes += read
                val elapsed = System.nanoTime() - windowStart
                if (elapsed >= PROGRESS_INTERVAL_NS) {
                    val speed = windowBytes * 1_000_000_000L / elapsed
                    val snapshot = done
                    patch(id) { t -> t.copy(doneBytes = snapshot, bytesPerSec = speed) }
                    windowStart = System.nanoTime()
                    windowBytes = 0
                }
            }
        }
        patch(id) { t -> t.copy(doneBytes = done) }
    }

    private fun finish(dir: DocumentFile, part: DocumentFile, fileName: String) {
        SafFiles.findChild(context, dir, fileName)?.delete()
        if (!part.renameTo(fileName)) throw IOException(texts.get(R.string.err_rename))
    }

    private fun findPart(task: DownloadTask): DocumentFile? =
        DocumentFile.fromTreeUri(context, Uri.parse(task.folderUri))?.let { SafFiles.findChild(context, it, task.fileName + PART_SUFFIX) }

    private fun patch(id: String, change: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpStatusException -> e.message.orEmpty()
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: texts.get(R.string.err_interrupted)
        else -> e.message ?: texts.get(R.string.err_unknown)
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val SAVE_DELAY_MS = 500L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_NS = 300_000_000L
        private const val MAX_RETRIES = 4
        private const val BACKOFF_SECONDS = 5L
        private const val MAX_NETWORK_RETRIES = 6
        private const val NETWORK_BACKOFF_SECONDS = 5L

        fun taskId(folderUri: String, fileName: String) = "$folderUri|$fileName"
    }
}
