package app.archivefetcher.addon

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class InstalledAddon(
    val id: String,
    val url: String,
    /** When the manifest was last downloaded, in epoch milliseconds. */
    val fetchedAt: Long,
    val manifest: AddonManifest,
) {
    /** Addons added from a file have no address to download a newer version from. */
    val canRefresh: Boolean get() = url.isNotBlank()
}

/**
 * Keeps one JSON file per installed manifest plus an index (address and fetch time of each), so addons work offline.
 * It does no locking of its own: the repository serializes access.
 */
class AddonStore(private val dir: File, private val allowLocalHttp: Boolean = false) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Index(val addons: List<IndexEntry> = emptyList())

    @Serializable
    private data class IndexEntry(val id: String, val url: String, val fetchedAt: Long)

    private val indexFile get() = File(dir, "index.json")

    /** Unreadable or no longer valid files are skipped instead of failing the whole list. */
    fun load(): List<InstalledAddon> =
        readIndex().addons.mapNotNull { entry ->
            runCatching {
                val manifest = ManifestParser.parse(manifestFile(entry.id).readText(), allowLocalHttp)
                InstalledAddon(entry.id, entry.url, entry.fetchedAt, manifest)
            }.getOrNull()
        }

    fun save(addon: InstalledAddon, rawManifest: String) {
        dir.mkdirs()
        writeAtomically(manifestFile(addon.id), rawManifest)
        val entry = IndexEntry(addon.id, addon.url, addon.fetchedAt)
        val current = readIndex().addons
        val updated = if (current.any { it.id == addon.id }) current.map { if (it.id == addon.id) entry else it } else current + entry
        writeAtomically(indexFile, json.encodeToString(Index(updated)))
    }

    fun remove(id: String) {
        manifestFile(id).delete()
        if (!indexFile.exists()) return
        writeAtomically(indexFile, json.encodeToString(Index(readIndex().addons.filterNot { it.id == id })))
    }

    private fun manifestFile(id: String): File {
        require(SAFE_ID.matches(id)) { "unsafe addon id" }
        return File(dir, "$id.json")
    }

    private fun readIndex(): Index =
        if (!indexFile.exists()) Index() else runCatching { json.decodeFromString<Index>(indexFile.readText()) }.getOrDefault(Index())

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(content)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    }
}
