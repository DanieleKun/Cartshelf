package app.archivefetcher.data

import app.archivefetcher.addon.CatalogConsole
import app.archivefetcher.source.SourceRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class IndexProgress(
    val running: Boolean = false,
    val doneConsoles: Int = 0,
    val totalConsoles: Int = 0,
    /** Name of the console being read right now. */
    val currentConsole: String? = null,
    /** Collections of the current console read so far, out of [packsInConsole]. */
    val packsDone: Int = 0,
    val packsInConsole: Int = 0,
    /** Collections that could not be read (removed, or login required) and are therefore missing from the index. */
    val skippedPacks: Int = 0,
) {
    /** 0..1 across the whole run, counting the collections of the current console. */
    val fraction: Float
        get() {
            if (totalConsoles == 0) return 0f
            val inConsole = if (packsInConsole > 0) packsDone.toFloat() / packsInConsole else 0f
            return ((doneConsoles + inConsole) / totalConsoles).coerceIn(0f, 1f)
        }
}

/**
 * Reads every collection of the installed addons once and keeps the file names for offline search.
 * Each console is written as soon as it is read, so an interrupted run resumes where it stopped (see [build]).
 */
class SearchIndexer(private val sources: SourceRegistry, private val store: IndexStore) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val runLock = Mutex()

    /**
     * Consoles are processed one after the other; within a console a few collections are read at the same time.
     * With [onlyMissing] the consoles already in the index are skipped, which is how a run resumes.
     */
    /**
     * Returns how many consoles could not be read at all (typically the connection dropped); they stay missing, so
     * running again with [onlyMissing] picks them up.
     */
    suspend fun build(consoles: List<CatalogConsole>, onlyMissing: Boolean): Int = runLock.withLock {
        val pending = consoles.filter { !onlyMissing || it.info.id !in store.indexed.value }
        _progress.value = IndexProgress(running = true, totalConsoles = pending.size)
        var unread = 0
        try {
            for (console in pending) {
                _progress.update {
                    it.copy(currentConsole = console.info.name, packsDone = 0, packsInConsole = console.entries.size)
                }
                if (!indexConsole(console) && console.entries.isNotEmpty()) unread++
                _progress.update { it.copy(doneConsoles = it.doneConsoles + 1, packsDone = 0, packsInConsole = 0) }
            }
        } finally {
            _progress.update { it.copy(running = false, currentConsole = null) }
        }
        unread
    }

    /** True when at least one collection was read and the console was written to the index. */
    private suspend fun indexConsole(console: CatalogConsole): Boolean {
        val gate = Semaphore(PARALLELISM)
        val guard = Mutex()
        val files = ArrayList<RomFile>()
        val seen = HashSet<String>()
        var failed = 0
        var succeeded = 0
        coroutineScope {
            console.entries.forEach { entry ->
                launch {
                    val item = gate.withPermit {
                        try {
                            sources.fetch(entry, cache = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // The worker runs on a thread pool, so the shared lists are only touched under the lock.
                    guard.withLock {
                        if (item == null) failed++ else {
                            succeeded++
                            item.files.filterTo(files) { seen.add(it.key) }
                        }
                    }
                    _progress.update { it.copy(packsDone = it.packsDone + 1) }
                }
            }
        }
        if (failed > 0) _progress.update { it.copy(skippedPacks = it.skippedPacks + failed) }
        if (succeeded > 0) withContext(Dispatchers.IO) { store.write(console.info.id, files.sortedBy { it.displayName.lowercase() }) }
        return succeeded > 0
    }

    private companion object {
        const val PARALLELISM = 3
    }
}
