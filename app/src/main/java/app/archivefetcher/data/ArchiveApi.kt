package app.archivefetcher.data

import app.archivefetcher.R
import app.archivefetcher.Texts
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Talks to the public Internet Archive metadata API and caches item listings in memory. */
class ArchiveApi(private val client: OkHttpClient, private val texts: Texts) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, ArchiveItem>()

    /** With [cache] off the result is not kept in memory, for bulk reads such as building the search index. */
    suspend fun fetchItem(entry: CatalogEntry, cache: Boolean = true): ArchiveItem {
        val cacheKey = "${entry.identifier}/${entry.subPath}/${entry.title}/${entry.format}"
        if (cache) this.cache[cacheKey]?.let { return it }
        val body = client.getString("$BASE/metadata/${encodeSegment(entry.identifier)}")
        val item = withContext(Dispatchers.Default) { parseItem(json.parseToJsonElement(body).jsonObject, entry) }
        val result = if (entry.expandZip) expandZips(item, entry) else item
        return result.also { if (cache) this.cache[cacheKey] = it }
    }

    /** Replaces each zip of the item by the files stored inside it, so they can be picked one by one. */
    private suspend fun expandZips(item: ArchiveItem, entry: CatalogEntry): ArchiveItem {
        val members = ArrayList<RomFile>()
        for (zip in item.files.filter { it.path.endsWith(".zip", ignoreCase = true) }) {
            val page = try {
                client.getStringLimited(downloadUrl(zip) + "/", ZipListing.MAX_BYTES)
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) throw IllegalStateException(texts.get(R.string.err_zip_login), e)
                throw e
            }
            withContext(Dispatchers.Default) {
                for (member in ZipListing.parse(page)) {
                    val shortName = member.name.substringAfterLast('/')
                    members += RomFile(
                        identifier = zip.identifier,
                        path = "${zip.path}/${member.name}",
                        size = member.size,
                        regions = RomNaming.regionsOf(shortName),
                        languages = RomLanguages.of(shortName, RomNaming.regionsOf(shortName)),
                        format = entry.format?.lowercase() ?: RomNaming.formatOf(member.name, entry.title),
                        packTitle = entry.title,
                        locked = zip.locked,
                    )
                }
            }
        }
        members += item.files.filterNot { it.path.endsWith(".zip", ignoreCase = true) }
        return item.copy(files = members.sortedBy { it.displayName.lowercase() })
    }

    internal fun parseItem(root: JsonObject, entry: CatalogEntry): ArchiveItem {
        val metadata = root["metadata"] as? JsonObject
        check(metadata != null) { texts.get(R.string.err_item_unavailable) }
        val locked = metadata["access-restricted-item"]?.jsonPrimitive?.contentOrNull == "true"
        val files = (root["files"] as? JsonArray ?: JsonArray(emptyList()))
            .map { it.jsonObject }
            .filter { isDownloadable(it, entry.subPath) }
            .map { f ->
                val name = f.getValue("name").jsonPrimitive.content
                val shortName = name.substringAfterLast('/')
                RomFile(
                    identifier = entry.identifier,
                    path = name,
                    size = f["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    regions = RomNaming.regionsOf(shortName),
                    languages = RomLanguages.of(shortName, RomNaming.regionsOf(shortName)),
                    format = entry.format?.lowercase() ?: RomNaming.formatOf(name, entry.title),
                    packTitle = entry.title,
                    locked = locked,
                    md5 = f["md5"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
            .sortedBy { it.displayName.lowercase() }
        return ArchiveItem(entry.identifier, locked, files)
    }

    private fun isDownloadable(file: JsonObject, path: String): Boolean {
        val name = file["name"]?.jsonPrimitive?.contentOrNull ?: return false
        if (!matchesPath(name, path)) return false
        if (file["source"]?.jsonPrimitive?.contentOrNull == "metadata") return false
        return skippedSuffixes.none { name.endsWith(it) } && !name.startsWith("__ia_")
    }

    fun downloadUrl(file: RomFile): String =
        "$BASE/download/${encodeSegment(file.identifier)}/" +
            file.path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    internal companion object {
        private const val BASE = "https://archive.org"

        /** A collection path is either a folder (everything below it) or one single file of the item. */
        fun matchesPath(name: String, path: String): Boolean =
            path.isEmpty() || name == path || name.startsWith("$path/")

        private val skippedSuffixes = listOf(
            "_meta.xml", "_files.xml", "_meta.sqlite", "_reviews.xml", ".torrent",
            ".jpg", ".jpeg", ".png", ".gif", ".txt", ".nfo",
        )
    }
}
