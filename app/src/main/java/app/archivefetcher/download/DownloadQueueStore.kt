package app.archivefetcher.download

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Keeps the download queue on disk, so downloads survive the app being closed by Android or the device restarting.
 * The file holds only what a download needs (address, destination, sizes, state); no credentials.
 */
class DownloadQueueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DownloadTask.serializer())
    private val lock = Any()

    /**
     * The saved queue as it should start again: work that was running goes back to the queue (its partial file is
     * resumed), paused and failed downloads stay as they were, the progress speed is reset.
     */
    fun load(): List<DownloadTask> = synchronized(lock) {
        val saved = runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }
            .getOrDefault(emptyList())
        saved.map { task ->
            when (task.state) {
                DownloadState.Running, DownloadState.Verifying, DownloadState.Extracting ->
                    task.copy(state = DownloadState.Queued, bytesPerSec = 0, message = null, extractProgress = 0f)
                else -> task.copy(bytesPerSec = 0)
            }
        }
    }

    fun save(tasks: List<DownloadTask>) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val kept = tasks.filter { it.state != DownloadState.Done } + tasks.filter { it.state == DownloadState.Done }.takeLast(MAX_DONE)
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(serializer, kept).toByteArray(Charsets.UTF_8))
                // On disk before the rename, so a power cut never leaves an empty queue file behind.
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        /** Finished downloads remembered for the Downloads screen; older ones are dropped. */
        const val MAX_DONE = 200
    }
}
