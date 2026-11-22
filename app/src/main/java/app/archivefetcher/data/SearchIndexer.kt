package app.archivefetcher.data

import app.archivefetcher.addon.CatalogConsole
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class IndexProgress(
    val running: Boolean = false,
    val doneConsoles: Int = 0,
    val totalConsoles: Int = 0,
    /** Collections that could not be read (removed, or login required) and are therefore missing from the index. */
    val skippedPacks: Int = 0,
)

/** Reads every collection of the installed addons once and keeps the file names for offline search. */
class SearchIndexer(private val archive: ArchiveApi, private val store: IndexStore) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** Consoles are processed one after the other; within a console a few collections are read at the same time. */
    suspend fun build(consoles: List<CatalogConsole>, onlyMissing: Boolean) {
        val pending = consoles.filter { !onlyMissing || it.info.id !in store.indexed.value }
        _progress.value = IndexProgress(running = true, totalConsoles = pending.size)
        try {
            for (console in pending) {
                indexConsole(console)
                _progress.update { it.copy(doneConsoles = it.doneConsoles + 1) }
            }
        } finally {
            _progress.update { it.copy(running = false) }
        }
    }

    private suspend fun indexConsole(console: CatalogConsole) {
        val gate = Semaphore(PARALLELISM)
        val files = ArrayList<RomFile>()
        val seen = HashSet<String>()
        var failed = 0
        var succeeded = 0
        coroutineScope {
            console.entries.forEach { entry ->
                launch {
                    val item = gate.withPermit {
                        try {
                            archive.fetchItem(entry, cache = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // Everything after the network call runs on one thread, so the plain collections are safe.
                    if (item == null) failed++ else {
                        succeeded++
                        item.files.filterTo(files) { seen.add(it.key) }
                    }
                }
            }
        }
        if (failed > 0) _progress.update { it.copy(skippedPacks = it.skippedPacks + failed) }
        if (succeeded > 0) withContext(Dispatchers.IO) { store.write(console.info.id, files.sortedBy { it.displayName.lowercase() }) }
    }

    private companion object {
        const val PARALLELISM = 3
    }
}
