package app.archivefetcher.addon

import app.archivefetcher.data.CatalogEntry
import app.archivefetcher.data.RemoteFile
import app.archivefetcher.data.SourceTypes

/**
 * A console as shown in the app. [id] is the stable key used for preferences such as the destination folder.
 * [thumbnails] is the libretro-thumbnails system an addon named for its box art, when it gave one.
 */
data class ConsoleInfo(val id: String, val name: String, val categoryId: String, val thumbnails: String? = null)

data class CatalogConsole(val info: ConsoleInfo, val entries: List<CatalogEntry>)

data class CatalogCategory(val id: String, val name: String, val consoles: List<CatalogConsole>)

/** Everything the installed addons offer, merged into one category → console tree. */
data class Catalog(val categories: List<CatalogCategory> = emptyList()) {
    fun category(id: String): CatalogCategory? = categories.firstOrNull { it.id == id }

    fun console(id: String): CatalogConsole? =
        categories.asSequence().flatMap { it.consoles.asSequence() }.firstOrNull { it.info.id == id }

    val isEmpty: Boolean get() = categories.isEmpty()
}

object CatalogMerger {
    private class ConsoleBuilder(val id: String, val name: String) {
        var thumbnails: String? = null
        val entries = ArrayList<CatalogEntry>()
        val seen = HashSet<Pair<String, String>>()
    }

    private class CategoryBuilder(val id: String, val name: String) {
        val consoles = LinkedHashMap<String, ConsoleBuilder>()
    }

    /**
     * Categories and consoles are merged by id, keeping the name and the order of the first addon that declared
     * them. A collection (same item and path) that several addons list is kept once. Consoles without collections
     * are left out.
     */
    /**
     * Turns a validated collection into a catalog entry. The identifier names the source, so the same item, page or
     * feed listed by two addons is shown once; an inline list is identified by its addon and title.
     */
    private fun entryOf(addonId: String, collection: ManifestCollection): CatalogEntry? {
        val source = collection.source
        return when (source.type) {
            SourceTypes.ARCHIVE_ITEM -> CatalogEntry(
                collection.title, source.id ?: return null, source.path.orEmpty(), collection.format,
                expandZip = source.expand == AddonSpec.EXPAND_ZIP,
            )
            // Addresses are stored normalized, the form every later request uses (already validated by the parser).
            SourceTypes.HTTP_DIRECTORY, SourceTypes.FILE_LIST -> CatalogEntry(
                collection.title, normalized(source.url) ?: return null, format = collection.format,
                sourceType = source.type, extensions = source.extensions.orEmpty(),
            )
            SourceTypes.FILES -> CatalogEntry(
                collection.title, "files:$addonId/${collection.title}", format = collection.format,
                sourceType = source.type,
                files = source.files.orEmpty().mapNotNull { file ->
                    normalized(file.url)?.let { RemoteFile(file.name, it, file.size ?: 0, file.md5?.lowercase()) }
                },
            )
            else -> null
        }
    }

    private fun normalized(url: String?): String? = url?.let { SourceUrl.parse(it, allowLocalHttp = true)?.toString() }

    fun merge(manifests: List<AddonManifest>): Catalog {
        val categories = LinkedHashMap<String, CategoryBuilder>()
        for (manifest in manifests) {
            for (category in manifest.categories) {
                val categoryBuilder = categories.getOrPut(category.id) { CategoryBuilder(category.id, category.name) }
                for (console in category.consoles) {
                    val consoleBuilder = categoryBuilder.consoles.getOrPut(console.id) { ConsoleBuilder(console.id, console.name) }
                    if (consoleBuilder.thumbnails == null) consoleBuilder.thumbnails = console.thumbnails?.trim()?.takeIf { it.isNotEmpty() }
                    for (collection in console.collections) {
                        val entry = entryOf(manifest.id, collection) ?: continue
                        if (consoleBuilder.seen.add("${entry.sourceType}|${entry.identifier}" to entry.subPath)) {
                            consoleBuilder.entries += entry
                        }
                    }
                }
            }
        }
        return Catalog(
            categories.values.mapNotNull { category ->
                val consoles = category.consoles.values
                    .filter { it.entries.isNotEmpty() }
                    .map { CatalogConsole(ConsoleInfo(it.id, it.name, category.id, it.thumbnails), it.entries.toList()) }
                if (consoles.isEmpty()) null else CatalogCategory(category.id, category.name, consoles)
            },
        )
    }
}
