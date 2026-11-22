package app.archivefetcher.addon

import app.archivefetcher.data.ResponseTooLargeException
import app.archivefetcher.data.getStringLimited
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class InstallOutcome(val addon: InstalledAddon, val replaced: Boolean)

/** Installs, refreshes and removes addons and publishes the merged catalog. */
class AddonRepository(
    private val store: AddonStore,
    private val client: OkHttpClient,
    private val allowLocalHttp: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    private val _catalog = MutableStateFlow(Catalog())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    suspend fun load() {
        mutex.withLock { publish(withContext(Dispatchers.IO) { store.load() }) }
    }

    /** Downloads, validates and stores the manifest at [urlText]. An addon with the same id is replaced. */
    suspend fun install(urlText: String): InstallOutcome {
        val url = AddonUrl.parse(urlText, allowLocalHttp)
        val (manifest, raw) = fetch(url)
        return save(InstalledAddon(manifest.id, url, clock(), manifest), raw)
    }

    /** Installs a manifest the user picked from a file. It has no address, so it can only be replaced, not refreshed. */
    suspend fun installFromText(text: String): InstallOutcome {
        if (text.length > AddonSpec.MAX_BYTES) throw AddonException(AddonException.Kind.TooLarge)
        val manifest = ManifestParser.parse(text)
        return save(InstalledAddon(manifest.id, "", clock(), manifest), text)
    }

    suspend fun refresh(id: String): InstalledAddon {
        val installed = _addons.value.firstOrNull { it.id == id }
            ?: throw AddonException(AddonException.Kind.Invalid, "addon not installed")
        if (!installed.canRefresh) throw AddonException(AddonException.Kind.Invalid, "added from a file")
        val (manifest, raw) = fetch(installed.url)
        if (manifest.id != id) {
            throw AddonException(AddonException.Kind.Invalid, "the addon id changed from '$id' to '${manifest.id}'")
        }
        return save(InstalledAddon(id, installed.url, clock(), manifest), raw).addon
    }

    /** Refreshes addons not updated for [maxAgeMillis]. A failure keeps the stored copy and is otherwise ignored. */
    suspend fun refreshStale(maxAgeMillis: Long) {
        val stale = _addons.value.filter { it.canRefresh && clock() - it.fetchedAt > maxAgeMillis }
        for (addon in stale) {
            try {
                refresh(addon.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or the host is down: the stored copy keeps working.
            }
        }
    }

    suspend fun remove(id: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) { store.remove(id) }
            publish(_addons.value.filterNot { it.id == id })
        }
    }

    private suspend fun fetch(url: String): Pair<AddonManifest, String> {
        val raw = try {
            client.getStringLimited(url, AddonSpec.MAX_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ResponseTooLargeException) {
            throw AddonException(AddonException.Kind.TooLarge)
        } catch (e: IOException) {
            throw AddonException(AddonException.Kind.Network, e.message)
        }
        return ManifestParser.parse(raw) to raw
    }

    private suspend fun save(addon: InstalledAddon, raw: String): InstallOutcome =
        mutex.withLock {
            val current = _addons.value
            val replaced = current.any { it.id == addon.id }
            withContext(Dispatchers.IO) { store.save(addon, raw) }
            publish(if (replaced) current.map { if (it.id == addon.id) addon else it } else current + addon)
            InstallOutcome(addon, replaced)
        }

    private fun publish(addons: List<InstalledAddon>) {
        _addons.value = addons
        _catalog.value = CatalogMerger.merge(addons.map { it.manifest })
    }
}
