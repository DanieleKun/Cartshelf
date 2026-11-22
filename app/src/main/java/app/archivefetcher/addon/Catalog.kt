package app.archivefetcher.addon

import app.archivefetcher.data.CatalogEntry

/** A console as shown in the app. [id] is the stable key used for preferences such as the destination folder. */
data class ConsoleInfo(val id: String, val name: String, val categoryId: String)

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
    fun merge(manifests: List<AddonManifest>): Catalog {
        val categories = LinkedHashMap<String, CategoryBuilder>()
        for (manifest in manifests) {
            for (category in manifest.categories) {
                val categoryBuilder = categories.getOrPut(category.id) { CategoryBuilder(category.id, category.name) }
                for (console in category.consoles) {
                    val consoleBuilder = categoryBuilder.consoles.getOrPut(console.id) { ConsoleBuilder(console.id, console.name) }
                    for (collection in console.collections) {
                        val id = collection.source.id ?: continue
                        val path = collection.source.path.orEmpty()
                        if (consoleBuilder.seen.add(id to path)) {
                            consoleBuilder.entries += CatalogEntry(
                                collection.title, id, path, collection.format,
                                expandZip = collection.source.expand == AddonSpec.EXPAND_ZIP,
                            )
                        }
                    }
                }
            }
        }
        return Catalog(
            categories.values.mapNotNull { category ->
                val consoles = category.consoles.values
                    .filter { it.entries.isNotEmpty() }
                    .map { CatalogConsole(ConsoleInfo(it.id, it.name, category.id), it.entries.toList()) }
                if (consoles.isEmpty()) null else CatalogCategory(category.id, category.name, consoles)
            },
        )
    }
}
