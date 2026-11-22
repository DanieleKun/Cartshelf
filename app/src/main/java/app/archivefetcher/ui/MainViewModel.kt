package app.archivefetcher.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.archivefetcher.App
import app.archivefetcher.R
import android.net.Uri
import app.archivefetcher.addon.AddonException
import app.archivefetcher.addon.AddonSpec
import app.archivefetcher.addon.Catalog
import app.archivefetcher.addon.CatalogConsole
import app.archivefetcher.addon.ConsoleInfo
import app.archivefetcher.addon.InstalledAddon
import app.archivefetcher.data.CatalogEntry
import app.archivefetcher.data.FileFilters
import app.archivefetcher.data.FrontendLayout
import app.archivefetcher.data.IndexHit
import app.archivefetcher.data.IndexProgress
import app.archivefetcher.data.LaterItem
import app.archivefetcher.data.SearchResult
import app.archivefetcher.download.LaterMode
import app.archivefetcher.data.RomFile
import app.archivefetcher.data.RomNaming
import app.archivefetcher.data.SearchQuery
import app.archivefetcher.data.GameEntries
import app.archivefetcher.data.GameEntry
import app.archivefetcher.data.Prefs
import app.archivefetcher.data.RomVariants
import app.archivefetcher.data.AddonReceiver
import app.archivefetcher.data.ReceiverPage
import app.archivefetcher.data.UpdateInfo
import java.util.Locale
import app.archivefetcher.download.IndexJobState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Categories : Screen
    data class Category(val categoryId: String) : Screen
    data class Console(val consoleId: String) : Screen
    data object Filters : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data object Addons : Screen
    data object Welcome : Screen
    data object Search : Screen
    data object Later : Screen
}

/** A collection that could not be read, with the reason shown to the user. */
data class FailedPack(val entry: CatalogEntry, val reason: String)

/** Every file of every collection of the console being browsed, filled in progressively. */
data class Library(
    val files: List<RomFile> = emptyList(),
    val loaded: Int = 0,
    val total: Int = 0,
    val failedPacks: List<FailedPack> = emptyList(),
    val loading: Boolean = false,
    val hasLocked: Boolean = false,
    val error: String? = null,
    /** Title of the collection that finished loading last, shown while the rest is on its way. */
    val lastPack: String? = null,
)

/** [entries] are the [files] as shown: the discs of a multi-disc game folded into one entry. */
data class VisibleFiles(val files: List<RomFile>, val approximate: Boolean, val entries: List<GameEntry> = GameEntries.group(files))

/** Result of the last add/update/remove on the Addons screen. */
data class AddonMessage(val text: String, val isError: Boolean)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as App).graph

    val stack = mutableStateListOf<Screen>(if (graph.prefs.onboardingDone) Screen.Categories else Screen.Welcome)
    val current: Screen get() = stack.last()

    val catalog: StateFlow<Catalog> get() = graph.addons.catalog
    val addons: StateFlow<List<InstalledAddon>> get() = graph.addons.addons

    var library by mutableStateOf(Library())
        private set

    private var filterState by mutableStateOf(graph.prefs.quickFilters)

    /** The quick switches are saved as they change, so every console opens with the same ones. */
    var filters: FileFilters
        get() = filterState
        set(value) {
            if (value.oneGamePerTitle != filterState.oneGamePerTitle || value.hideUnfinished != filterState.hideUnfinished ||
                value.hideUnofficial != filterState.hideUnofficial
            ) {
                graph.prefs.quickFilters = value
            }
            filterState = value
        }
    var query by mutableStateOf("")
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Lower-case names (without extension) of what is already in the destination folder of the console being browsed. */
    var present by mutableStateOf<Set<String>>(emptySet())
        private set

    // Global search and the "download later" list.
    var searchQuery by mutableStateOf("")
        private set
    var searchResult by mutableStateOf(SearchResult(emptyList(), 0))
        private set
    var searching by mutableStateOf(false)
        private set

    /** Files ticked in the search results, kept while the query changes so several searches can be combined. */
    var picked by mutableStateOf<Map<String, IndexHit>>(emptyMap())
        private set
    var laterPicked by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Short message about the last action (saved for later, skipped consoles…), shown in the action bars. */
    var notice by mutableStateOf<String?>(null)

    val indexed: StateFlow<Set<String>> get() = graph.index.indexed
    val indexProgress: StateFlow<IndexProgress> get() = graph.indexer.progress
    val later: StateFlow<List<LaterItem>> get() = graph.later.items

    /** Whether the background index job is waiting (for example for a network) or running. */
    val indexJob: StateFlow<IndexJobState> =
        graph.indexScheduler.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), IndexJobState.Idle)

    private var searchJob: Job? = null

    var addonUrl by mutableStateOf("")
    var addonBusy by mutableStateOf(false)
        private set
    var addonMessage by mutableStateOf<AddonMessage?>(null)
        private set

    /** Bumped when a destination folder changes so screens re-read it from preferences. */
    var folderRevision by mutableIntStateOf(0)
        private set

    private var libraryJob: Job? = null

    /**
     * A newer release on GitHub, shown on the home screen until opened or dismissed. Declared before `init`: the check
     * there can finish without suspending (already checked today) and assign it during construction.
     */
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    init {
        graph.downloads.resumeRestored()
        viewModelScope.launch {
            // Keeps the system schedule in step with the list, including after a restart.
            graph.later.items.collect { graph.laterScheduler.apply(it.isNotEmpty()) }
        }
        viewModelScope.launch {
            graph.addons.load()
            graph.addons.refreshStale(REFRESH_AFTER_MS)
        }
        viewModelScope.launch {
            // Consoles added to the index by the background job become searchable without retyping.
            graph.index.indexed.drop(1).collect { if (searchQuery.isNotBlank()) onSearchQuery(searchQuery) }
        }
        viewModelScope.launch { updateInfo = graph.updates.check() }
    }

    override fun onCleared() {
        receiver.stop()
        super.onCleared()
    }

    // ---- New version ---------------------------------------------------------------------------------------

    fun dismissUpdate() {
        updateInfo?.let(graph.updates::dismiss)
        updateInfo = null
    }

    // ---- Addon from the phone ------------------------------------------------------------------------------

    private val receiver = AddonReceiver(viewModelScope)

    /** The address to open on the phone while the receiver runs; null when it is off. */
    var receiverAddress by mutableStateOf<String?>(null)
        private set

    /** True when the receiver could not start because the device is not on a local network. */
    var receiverUnavailable by mutableStateOf(false)
        private set

    /** An addon file sent from the phone, waiting for the user to confirm it on the device. */
    var receivedAddon by mutableStateOf<String?>(null)
        private set

    fun startReceiver() {
        val page = ReceiverPage(
            title = graph.texts.get(R.string.receive_page_title),
            intro = graph.texts.get(R.string.receive_page_intro),
            urlLabel = graph.texts.get(R.string.receive_page_url),
            send = graph.texts.get(R.string.receive_page_send),
            fileLabel = graph.texts.get(R.string.receive_page_file),
            sent = graph.texts.get(R.string.receive_page_sent),
            notFound = graph.texts.get(R.string.receive_page_expired),
        )
        receiverAddress = receiver.start(page, onStopped = { receiverAddress = null }) { text -> onReceived(text) }
        receiverUnavailable = receiverAddress == null
    }

    fun stopReceiver() {
        receiver.stop()
        receiverAddress = null
        receiverUnavailable = false
    }

    /** An address only fills the form; a file waits for confirmation. Nothing is installed without the user. */
    private fun onReceived(text: String) {
        if (text.startsWith("http://") || text.startsWith("https://")) {
            addonUrl = text.take(MAX_RECEIVED_URL)
            addonMessage = AddonMessage(graph.texts.get(R.string.receive_got_url), false)
        } else {
            receivedAddon = text
            addonMessage = null
        }
    }

    fun installReceived() {
        val text = receivedAddon ?: return
        if (addonBusy) return
        receivedAddon = null
        runAddonAction {
            val outcome = graph.addons.installFromText(text)
            val name = outcome.addon.manifest.name
            AddonMessage(graph.texts.get(if (outcome.replaced) R.string.addon_updated else R.string.addon_added, name), false)
        }
    }

    fun discardReceived() {
        receivedAddon = null
    }

    fun open(screen: Screen) {
        notice = null
        if (screen is Screen.Console) {
            val console = catalog.value.console(screen.consoleId) ?: return
            loadLibrary(console)
        }
        stack.add(screen)
    }

    /** Returns false when already at the root, so the caller can let the system handle back. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        notice = null
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---- Addons -------------------------------------------------------------------------------------------

    /** Called for a cartshelf://addon?url=… link: pre-fills the form, the user still has to confirm. */
    fun requestAddon(url: String) {
        addonUrl = url
        addonMessage = null
        if (current != Screen.Addons) open(Screen.Addons)
    }

    fun installAddon() {
        val url = addonUrl.trim()
        if (url.isEmpty() || addonBusy) return
        runAddonAction {
            val outcome = graph.addons.install(url)
            addonUrl = ""
            val name = outcome.addon.manifest.name
            AddonMessage(graph.texts.get(if (outcome.replaced) R.string.addon_updated else R.string.addon_added, name), false)
        }
    }

    /** Installs a manifest picked with the system file picker; useful when the addon is not reachable by address. */
    fun installAddonFromFile(uri: Uri) {
        if (addonBusy) return
        runAddonAction {
            val text = withContext(Dispatchers.IO) { readLimited(uri, AddonSpec.MAX_BYTES) }
                ?: throw AddonException(AddonException.Kind.TooLarge)
            val outcome = graph.addons.installFromText(text)
            val name = outcome.addon.manifest.name
            AddonMessage(graph.texts.get(if (outcome.replaced) R.string.addon_updated else R.string.addon_added, name), false)
        }
    }

    /** Null when the content is larger than [limit]. */
    private fun readLimited(uri: Uri, limit: Long): String? {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IOException(graph.texts.get(R.string.err_addon_read))
        val bytes = stream.use { it.readAtMost(limit + 1) }
        return if (bytes.size > limit) null else bytes.toString(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readAtMost(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), max - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun updateAddon(id: String) {
        if (addonBusy) return
        runAddonAction {
            val addon = graph.addons.refresh(id)
            AddonMessage(graph.texts.get(R.string.addon_updated, addon.manifest.name), false)
        }
    }

    fun removeAddon(id: String) {
        viewModelScope.launch {
            graph.addons.remove(id)
            addonMessage = null
        }
    }

    private fun runAddonAction(action: suspend () -> AddonMessage) {
        addonBusy = true
        addonMessage = null
        viewModelScope.launch {
            addonMessage = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AddonException) {
                AddonMessage(addonErrorText(e), true)
            } catch (e: Exception) {
                AddonMessage(graph.texts.get(R.string.addon_failed, errorText(e)), true)
            } finally {
                addonBusy = false
            }
        }
    }

    private fun addonErrorText(e: AddonException): String {
        val reason = when (e.kind) {
            AddonException.Kind.InvalidUrl -> graph.texts.get(R.string.err_addon_url)
            AddonException.Kind.InsecureUrl -> graph.texts.get(R.string.err_addon_https)
            AddonException.Kind.TooLarge -> graph.texts.get(R.string.err_addon_too_large)
            AddonException.Kind.NewerSpec -> graph.texts.get(R.string.err_addon_newer)
            AddonException.Kind.Invalid -> graph.texts.get(R.string.err_addon_invalid, e.detail.orEmpty())
            AddonException.Kind.Network -> graph.texts.get(R.string.err_network, e.detail.orEmpty())
        }
        return graph.texts.get(R.string.addon_failed, reason)
    }

    // ---- Console library ----------------------------------------------------------------------------------

    fun reloadLibrary() {
        val screen = current as? Screen.Console ?: return
        catalog.value.console(screen.consoleId)?.let { loadLibrary(it) }
    }

    /** Tries again only the collections that failed, keeping everything already loaded. */
    fun retryFailed() {
        val pending = library.failedPacks
        if (pending.isEmpty() || library.loading) return
        val base = library
        library = base.copy(failedPacks = emptyList(), loading = true, loaded = 0, total = pending.size)
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            val gate = Semaphore(LOAD_PARALLELISM)
            val seen = base.files.mapTo(HashSet()) { it.key }
            val merged = ArrayList(base.files)
            val failed = ArrayList<FailedPack>()
            var hasLocked = base.hasLocked
            var loaded = 0
            coroutineScope {
                pending.forEach { pack ->
                    launch {
                        val outcome = gate.withPermit {
                            try {
                                Result.success(graph.sources.fetch(pack.entry))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                        loaded++
                        outcome.fold(
                            onSuccess = { item ->
                                hasLocked = hasLocked || item.locked
                                item.files.filterTo(merged) { seen.add(it.key) }
                            },
                            onFailure = { error -> failed += FailedPack(pack.entry, errorText(error)) },
                        )
                        library = library.copy(loaded = loaded)
                    }
                }
            }
            library = Library(
                files = merged.sortedBy { it.displayName.lowercase() },
                loaded = pending.size, total = pending.size,
                failedPacks = failed, loading = false, hasLocked = hasLocked,
            )
        }
    }

    private fun loadLibrary(console: CatalogConsole) {
        libraryJob?.cancel()
        filters = filters.cleared()
        query = ""
        selected = emptySet()
        val entries = console.entries
        library = Library(total = entries.size, loading = entries.isNotEmpty())
        libraryJob = viewModelScope.launch {
            try {
                val gate = Semaphore(LOAD_PARALLELISM)
                // Everything below runs on the main thread, so these plain collections need no locking.
                val merged = ArrayList<RomFile>()
                val seen = HashSet<String>()
                var loaded = 0
                val failedPacks = ArrayList<FailedPack>()
                var hasLocked = false
                var lastPublish = 0L
                coroutineScope {
                    entries.forEach { entry ->
                        launch {
                            val outcome = gate.withPermit {
                                try {
                                    Result.success(graph.sources.fetch(entry))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            }
                            loaded++
                            outcome.fold(
                                onSuccess = { item ->
                                    hasLocked = hasLocked || item.locked
                                    item.files.filterTo(merged) { seen.add(it.key) }
                                },
                                onFailure = { error -> failedPacks += FailedPack(entry, errorText(error)) },
                            )
                            // Publishing copies the list, so do it at most a few times per second.
                            val now = System.nanoTime()
                            if (loaded == entries.size || now - lastPublish > PUBLISH_INTERVAL_NS) {
                                lastPublish = now
                                library = Library(
                                    files = merged.toList(), loaded = loaded, total = entries.size,
                                    failedPacks = failedPacks.toList(), loading = loaded < entries.size, hasLocked = hasLocked,
                                    lastPack = entry.title,
                                )
                            }
                        }
                    }
                }
                val sorted = merged.sortedBy { it.displayName.lowercase() }
                library = library.copy(files = sorted, loading = false)
                // A fully loaded console is also what the search needs, so keep it for later searches.
                if (failedPacks.isEmpty() && sorted.isNotEmpty()) {
                    withContext(Dispatchers.IO) { graph.index.write(console.info.id, sorted) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                library = Library(error = errorText(e))
            }
        }
    }

    fun toggle(file: RomFile) {
        selected = if (file.key in selected) selected - file.key else selected + file.key
    }

    fun select(files: List<RomFile>) {
        selected = selected + files.map { it.key }
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun setFolder(console: ConsoleInfo, uri: String) {
        graph.prefs.setFolder(console.id, uri)
        folderRevision++
    }

    fun hasDestination(console: ConsoleInfo): Boolean = graph.folders.hasDestination(console.id)

    // ---- Library folder, layout, first run -----------------------------------------------------------------

    fun setBaseFolder(uri: String) {
        graph.prefs.baseFolder = uri
        folderRevision++
    }

    fun setLayout(layout: FrontendLayout) {
        graph.prefs.layoutId = layout.id
        folderRevision++
    }

    fun finishOnboarding() {
        graph.prefs.onboardingDone = true
        stack.clear()
        stack.add(Screen.Categories)
    }

    // ---- Files already in the folder ---------------------------------------------------------------------

    fun isPresent(file: RomFile): Boolean = RomNaming.stemOf(file.displayName).lowercase() in present

    private val nameQuery: SearchQuery get() = SearchQuery.parse(query)

    fun isVisible(file: RomFile): Boolean = isVisible(file, nameQuery, fuzzy = false)

    private fun isVisible(file: RomFile, parsed: SearchQuery, fuzzy: Boolean): Boolean =
        filters.matches(file) &&
            (parsed.isEmpty || (if (fuzzy) parsed.matchesFuzzy(file.searchName) else parsed.matches(file.searchName)))

    /**
     * Files that pass the filters and the name search. When the name matches nothing exactly, names within a typo or
     * two are shown instead and [VisibleFiles.approximate] says so.
     */
    fun visibleFiles(files: List<RomFile>): VisibleFiles {
        val parsed = nameQuery
        val exact = files.filter { isVisible(it, parsed, fuzzy = false) }
        if (exact.isNotEmpty() || parsed.isEmpty || !parsed.canBeFuzzy) return visible(exact, approximate = false)
        val close = files.filter { isVisible(it, parsed, fuzzy = true) }
        return visible(close, approximate = close.isNotEmpty())
    }

    /**
     * "One version per game" picks among what the other filters and the search kept; "hide present" comes after it,
     * so a game already in the folder disappears instead of being replaced by its next-best version.
     */
    private fun visible(files: List<RomFile>, approximate: Boolean): VisibleFiles {
        val best = if (filters.oneGamePerTitle) RomVariants.bestPerTitle(files, preferredLanguage) else files
        val kept = if (filters.hidePresent) best.filterNot(::isPresent) else best
        return VisibleFiles(kept, approximate, GameEntries.group(kept))
    }

    /** The app's language as a ROM language code ("It", "En"…), used to pick the best version of a game. */
    val preferredLanguage: String
        get() {
            val code = graph.prefs.language.takeUnless { it == Prefs.LANGUAGE_SYSTEM } ?: Locale.getDefault().language
            return code.take(2).replaceFirstChar { it.uppercase() }
        }

    /** Selects or clears every file of an entry: all the discs of a multi-disc game at once. */
    fun toggle(entry: GameEntry) {
        val keys = entry.files.map { it.key }
        selected = if (keys.all { it in selected }) selected - keys.toSet() else selected + keys
    }

    /** Reads the destination folder of the console again; a folder that does not exist yet simply has nothing in it. */
    fun refreshPresent(console: ConsoleInfo) {
        viewModelScope.launch {
            present = withContext(Dispatchers.IO) {
                val folder = graph.folders.resolve(console.id, console.name, create = false)
                if (folder == null) emptySet() else graph.folders.presentStems(folder)
            }
        }
    }

    fun enqueue(console: ConsoleInfo, files: List<RomFile>) {
        viewModelScope.launch {
            if (enqueueNow(console, files)) selected = emptySet()
        }
    }

    /** Resolves the destination (creating its folder) and queues the files; false when there is no destination. */
    private suspend fun enqueueNow(console: ConsoleInfo, files: List<RomFile>): Boolean {
        val folder = withContext(Dispatchers.IO) { graph.folders.resolve(console.id, console.name, create = true) } ?: return false
        files.forEach { file ->
            graph.downloads.enqueue(console.name, folder, file.displayName, graph.sources.downloadUrl(file), file.size, file.md5)
        }
        folderRevision++
        return true
    }

    // ---- Download later ------------------------------------------------------------------------------------

    fun addLater(console: ConsoleInfo, files: List<RomFile>) {
        val added = graph.later.add(files.map { LaterItem.of(console.id, console.name, it) })
        selected = emptySet()
        notice = graph.texts.get(R.string.later_saved, added, files.size - added)
    }

    fun toggleLaterPick(key: String) {
        laterPicked = if (key in laterPicked) laterPicked - key else laterPicked + key
    }

    fun selectAllLater() {
        laterPicked = graph.later.items.value.mapTo(HashSet()) { it.key }
    }

    fun clearLaterPicks() {
        laterPicked = emptySet()
    }

    fun removeLater() {
        graph.later.remove(laterPicked)
        laterPicked = emptySet()
    }

    /** Starts the downloads of the ticked items, or of the whole list when none is ticked. */
    fun downloadLater() {
        val all = graph.later.items.value
        val items = if (laterPicked.isEmpty()) all else all.filter { it.key in laterPicked }
        viewModelScope.launch {
            val outcome = graph.laterRunner.start(items)
            laterPicked = emptySet()
            notice = if (outcome.skipped > 0) graph.texts.get(R.string.later_started_skipped, outcome.started, outcome.skipped)
            else graph.texts.get(R.string.later_started, outcome.started)
        }
    }

    var laterMode by mutableStateOf(LaterMode.fromId(graph.prefs.laterMode))
        private set
    var laterHour by mutableIntStateOf(graph.prefs.laterHour)
        private set

    fun chooseLaterMode(mode: LaterMode) {
        laterMode = mode
        graph.prefs.laterMode = mode.id
        viewModelScope.launch { graph.laterScheduler.apply(graph.later.items.value.isNotEmpty()) }
    }

    fun changeLaterHour(delta: Int) {
        laterHour = (laterHour + delta + 24) % 24
        graph.prefs.laterHour = laterHour
        viewModelScope.launch { graph.laterScheduler.apply(graph.later.items.value.isNotEmpty()) }
    }

    // ---- Global search -------------------------------------------------------------------------------------

    fun onSearchQuery(text: String) {
        searchQuery = text
        searchJob?.cancel()
        if (SearchQuery.parse(text).isEmpty) {
            searchResult = SearchResult(emptyList(), 0)
            searching = false
            return
        }
        val order = catalog.value.categories.flatMap { category -> category.consoles.map { it.info.id } }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searching = true
            try {
                searchResult = withContext(Dispatchers.IO) {
                    graph.index.search(text, SEARCH_LIMIT, order) { ensureActive() }
                }
            } finally {
                searching = false
            }
        }
    }

    fun consoleName(consoleId: String): String = catalog.value.console(consoleId)?.info?.name ?: consoleId

    fun togglePick(hit: IndexHit) {
        picked = if (hit.file.key in picked) picked - hit.file.key else picked + (hit.file.key to hit)
    }

    fun clearPicks() {
        picked = emptyMap()
    }

    fun laterFromPicks() {
        val items = picked.values.map { LaterItem.of(it.consoleId, consoleName(it.consoleId), it.file) }
        val added = graph.later.add(items)
        picked = emptyMap()
        notice = graph.texts.get(R.string.later_saved, added, items.size - added)
    }

    fun downloadPicks() {
        val chosen = picked.values.toList()
        viewModelScope.launch {
            var skipped = 0
            var started = 0
            chosen.groupBy { it.consoleId }.forEach { (consoleId, hits) ->
                val console = catalog.value.console(consoleId)?.info
                if (console != null && enqueueNow(console, hits.map { it.file })) started += hits.size else skipped += hits.size
            }
            picked = emptyMap()
            notice = if (skipped > 0) graph.texts.get(R.string.later_started_skipped, started, skipped)
            else graph.texts.get(R.string.later_started, started)
        }
    }

    // ---- Search index --------------------------------------------------------------------------------------

    /**
     * Starts the background job that reads the consoles still missing from the index. It runs as a foreground job,
     * survives the screen turning off and the app closing, and picks up where it stopped if Android interrupts it.
     */
    fun buildIndex() {
        graph.indexScheduler.start()
    }

    fun cancelIndex() {
        graph.indexScheduler.stop()
    }

    /**
     * Reads every console again, for when the addons' collections changed. Consoles are replaced one at a time and
     * nothing is deleted first, so searching keeps working during the refresh and an accidental press loses nothing.
     */
    fun rebuildIndex() {
        graph.indexScheduler.start(full = true)
    }

    private fun errorText(e: Throwable): String =
        e.message ?: graph.texts.get(R.string.err_network, e::class.simpleName ?: "?")

    private companion object {
        /** Number of collections fetched at once while building a console's file list. */
        const val LOAD_PARALLELISM = 3
        const val PUBLISH_INTERVAL_NS = 300_000_000L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_LIMIT = 300
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_RECEIVED_URL = 2000

        /** Addons are checked again at startup when their stored copy is older than this. */
        const val REFRESH_AFTER_MS = 12L * 60 * 60 * 1000
    }
}
