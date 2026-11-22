package app.archivefetcher.download

import app.archivefetcher.data.ArchiveApi
import app.archivefetcher.data.LaterItem
import app.archivefetcher.data.LaterStore
import app.archivefetcher.data.LibraryFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Moves files from the "download later" list into the download queue. Used by the button and by the scheduled worker.
 * An entry leaves the list only when its download is complete, so nothing is lost if the process is killed meanwhile.
 */
class LaterRunner(
    private val folders: LibraryFolders,
    private val downloads: DownloadManager,
    private val archive: ArchiveApi,
    private val later: LaterStore,
    scope: CoroutineScope,
) {
    data class Outcome(val started: Int, val skipped: Int)

    init {
        scope.launch {
            downloads.tasks.collect { tasks ->
                val finished = tasks.mapNotNullTo(HashSet()) { task -> task.laterKey?.takeIf { task.state == DownloadState.Done } }
                if (finished.isNotEmpty() && later.items.value.any { it.key in finished }) later.remove(finished)
            }
        }
    }

    /** Items whose console has no destination folder are not started and counted as skipped. */
    suspend fun start(items: List<LaterItem>): Outcome {
        var skipped = 0
        var started = 0
        items.groupBy { it.consoleId }.forEach { (consoleId, group) ->
            val name = group.first().consoleName
            val folder = withContext(Dispatchers.IO) { folders.resolve(consoleId, name, create = true) }
            if (folder == null) {
                skipped += group.size
            } else {
                group.forEach { item ->
                    val file = item.toRomFile()
                    downloads.enqueue(name, folder, file.displayName, archive.downloadUrl(file), file.size, file.md5, item.key)
                    started++
                }
            }
        }
        return Outcome(started, skipped)
    }
}
