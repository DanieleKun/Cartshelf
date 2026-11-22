package app.archivefetcher.data

import app.archivefetcher.addon.CatalogConsole
import app.archivefetcher.source.SourceRegistry
import java.util.concurrent.atomic.AtomicInteger
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
    /** Name of the console started most recently. */
    val currentConsole: String? = null,
    /** Collections read so far, out of [packsTotal], across the whole run. */
    val packsDone: Int = 0,
    val packsTotal: Int = 0,
    /** Collections that could not be read (removed, or login required) and are therefore missing from the index. */
    val skippedPacks: Int = 0,
) {
    /** 0..1 across the whole run, by collections read. */
    val fraction: Float
        get() = when {
            packsTotal > 0 -> (packsDone.toFloat() / packsTotal).coerceIn(0f, 1f)
            totalConsoles > 0 -> (doneConsoles.toFloat() / totalConsoles).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * Reads every collection of the installed addons once and keeps the file names for offline search.
 * Each console is written as soon as all its collections are read, so an interrupted run resumes where it stopped.
 *
 * Several consoles are read at the same time and collections share one global limit of parallel requests, so the
 * many consoles with a single collection no longer wait in line behind each other. The limit stays below the HTTP
 * client's per-host limit, leaving connections free for what the user is browsing meanwhile.
 */
class SearchIndexer(private val sources: SourceRegistry, private val store: IndexStore) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val runLock = Mutex()

    /**
     * With [onlyMissing] the consoles already in the index are skipped, which is how a run resumes. Returns how many
     * consoles could not be read at all (typically the connection dropped); they stay missing, so a later run with
     * [onlyMissing] picks them up.
     */
    suspend fun build(consoles: List<CatalogConsole>, onlyMissing: Boolean): Int = runLock.withLock {
        val pending = consoles.filter { !onlyMissing || it.info.id !in store.indexed.value }
        _progress.value = IndexProgress(
            running = true,
            totalConsoles = pending.size,
            packsTotal = pending.sumOf { it.entries.size },
        )
        val unread = AtomicInteger()
        val requests = Semaphore(REQUEST_PARALLELISM)
        val consoleSlots = Semaphore(CONSOLE_PARALLELISM)
        try {
            coroutineScope {
                for (console in pending) {
                    launch {
                        consoleSlots.withPermit {
                            _progress.update { it.copy(currentConsole = console.info.name) }
                            if (!indexConsole(console, requests) && console.entries.isNotEmpty()) unread.incrementAndGet()
                            _progress.update { it.copy(doneConsoles = it.doneConsoles + 1) }
                        }
                    }
                }
            }
        } finally {
            _progress.update { it.copy(running = false, currentConsole = null) }
        }
        unread.get()
    }

    /** True when at least one collection was read and the console was written to the index. */
    private suspend fun indexConsole(console: CatalogConsole, requests: Semaphore): Boolean {
        val guard = Mutex()
        val files = ArrayList<RomFile>()
        val seen = HashSet<String>()
        var failed = 0
        var succeeded = 0
        coroutineScope {
            console.entries.forEach { entry ->
                launch {
                    val item = requests.withPermit {
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
        /** Listings fetched at once across all consoles; Internet Archive handles this politely. */
        const val REQUEST_PARALLELISM = 6

        /** Consoles in progress at once; bounds the memory held by file lists not written yet. */
        const val CONSOLE_PARALLELISM = 4
    }
}
