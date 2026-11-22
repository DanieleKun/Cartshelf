package app.archivefetcher.source

import app.archivefetcher.addon.ManifestFile
import app.archivefetcher.addon.SourceUrl
import app.archivefetcher.data.ArchiveItem
import app.archivefetcher.data.CatalogEntry
import app.archivefetcher.data.RemoteFile
import app.archivefetcher.data.RomFile
import app.archivefetcher.data.RomLanguages
import app.archivefetcher.data.RomNaming
import app.archivefetcher.data.SourceTypes
import app.archivefetcher.data.getStringLimited
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** Shared rules and parsers for files that come from the web rather than from the Internet Archive. */
object RemoteFiles {
    const val MAX_LIST_BYTES = 16L * 1024 * 1024
    const val MAX_PAGE_BYTES = 8L * 1024 * 1024
    const val MAX_FILES = 100_000
    private const val MAX_NAME = 300
    private val md5 = Regex("^[0-9a-fA-F]{32}$")
    private val href = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true }

    /** A plain file name: no folders, no "." or "..", nothing that could escape the destination folder. */
    fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name.length <= MAX_NAME && '/' !in name && '\\' !in name && '\u0000' !in name &&
            name != "." && name != ".."

    fun isMd5(value: String): Boolean = md5.matches(value)

    /**
     * Entries that break a rule (missing or mistyped fields, unsafe name, non-public or non-https address) are skipped,
     * not fatal. Addresses are stored in their normalized form, the same one the download will use.
     */
    fun parseFileList(text: String, allowLocalHttp: Boolean): List<RemoteFile> {
        val entries = runCatching { json.parseToJsonElement(text).jsonObject["files"]?.jsonArray }.getOrNull()
            ?: throw IllegalStateException("the file list is not valid JSON in the expected format")
        val seen = HashSet<String>()
        return entries.asSequence()
            .mapNotNull { element -> runCatching { json.decodeFromJsonElement<ManifestFile>(element) }.getOrNull() }
            .mapNotNull { entry ->
                val url = SourceUrl.parse(entry.url, allowLocalHttp)
                if (url == null || !isSafeName(entry.name) || !seen.add(entry.name)) null
                else RemoteFile(entry.name, url.toString(), (entry.size ?: 0).coerceAtLeast(0), entry.md5?.takeIf(::isMd5)?.lowercase())
            }
            .take(MAX_FILES)
            .toList()
    }

    /**
     * Reads an "index of" page: every link to a file with one of the [extensions], on the same host as the page.
     * Links to folders, other sites, sorting options and parent folders are ignored.
     */
    fun parseDirectory(html: String, pageUrl: String, extensions: List<String>, allowLocalHttp: Boolean): List<RemoteFile> {
        val base = SourceUrl.parse(pageUrl, allowLocalHttp) ?: return emptyList()
        val wanted = extensions.map { it.lowercase() }.toSet()
        val seen = HashSet<String>()
        return href.findAll(html).asSequence()
            .mapNotNull { match -> base.resolve(unescape(match.groupValues[1]).trim()) }
            .filter {
                it.scheme == base.scheme && it.host == base.host && it.port == base.port && it.query == null &&
                    it.pathSegments.lastOrNull().orEmpty().isNotEmpty()
            }
            .mapNotNull { url ->
                val name = url.pathSegments.last()
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in wanted || !isSafeName(name) || !SourceUrl.isAllowed(url.toString(), allowLocalHttp)) null
                else RemoteFile(name, url.toString())
            }
            // One file per name: two links with the same name would be saved over each other on the device.
            .filter { seen.add(it.name) }
            .take(MAX_FILES)
            .toList()
    }

    private fun unescape(text: String): String =
        text.replace("&amp;", "&").replace("&#39;", "'").replace("&#039;", "'").replace("&quot;", "\"")
}

/** Directory pages, file-list feeds and inline lists. None of them needs an account or extra headers. */
class WebSources(private val client: OkHttpClient, private val allowLocalHttp: Boolean) : SourceProvider {
    override val types = setOf(SourceTypes.HTTP_DIRECTORY, SourceTypes.FILE_LIST, SourceTypes.FILES)
    private val cache = ConcurrentHashMap<String, ArchiveItem>()

    override suspend fun fetch(entry: CatalogEntry, cache: Boolean): ArchiveItem {
        // Everything that changes the result is in the key, so a refreshed addon never gets an old listing.
        val cacheKey = "${entry.sourceType}|${entry.identifier}|${entry.title}|${entry.format}|${entry.extensions}|${entry.files.hashCode()}"
        if (cache) this.cache[cacheKey]?.let { return it }
        val remote = when (entry.sourceType) {
            SourceTypes.FILES -> entry.files
            SourceTypes.FILE_LIST -> {
                val body = client.getStringLimited(entry.identifier, RemoteFiles.MAX_LIST_BYTES)
                withContext(Dispatchers.Default) { RemoteFiles.parseFileList(body, allowLocalHttp) }
            }
            SourceTypes.HTTP_DIRECTORY -> {
                val body = client.getStringLimited(entry.identifier, RemoteFiles.MAX_PAGE_BYTES)
                withContext(Dispatchers.Default) { RemoteFiles.parseDirectory(body, entry.identifier, entry.extensions, allowLocalHttp) }
            }
            else -> throw UnsupportedSourceException(entry.sourceType)
        }
        val files = withContext(Dispatchers.Default) {
            remote.map { toRomFile(it, entry) }.sortedBy { it.displayName.lowercase() }
        }
        return ArchiveItem(entry.identifier, locked = false, files = files).also { if (cache) this.cache[cacheKey] = it }
    }

    private fun toRomFile(file: RemoteFile, entry: CatalogEntry): RomFile {
        val regions = RomNaming.regionsOf(file.name)
        return RomFile(
            identifier = entry.identifier,
            path = file.name,
            size = file.size,
            regions = regions,
            languages = RomLanguages.of(file.name, regions),
            format = entry.format?.lowercase() ?: RomNaming.formatOf(file.name, entry.title),
            packTitle = entry.title,
            locked = false,
            md5 = file.md5,
            url = file.url,
        )
    }
}
