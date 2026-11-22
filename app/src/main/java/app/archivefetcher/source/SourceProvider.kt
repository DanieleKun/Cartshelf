package app.archivefetcher.source

import app.archivefetcher.data.ArchiveApi
import app.archivefetcher.data.ArchiveItem
import app.archivefetcher.data.CatalogEntry
import app.archivefetcher.data.RomFile

/**
 * Lists the files of a collection for one or more source types. Providers only read listings; downloading is
 * the same plain HTTPS request for every source, so a provider never runs code from an addon.
 */
interface SourceProvider {
    val types: Set<String>

    /** With [cache] off the result is not kept in memory, for bulk reads such as building the search index. */
    suspend fun fetch(entry: CatalogEntry, cache: Boolean = true): ArchiveItem
}

class UnsupportedSourceException(type: String) : IllegalStateException("unsupported source type '$type'")

/** Routes every collection to the provider of its source type, and gives each file its download address. */
class SourceRegistry(private val archive: ArchiveApi, others: List<SourceProvider>) {
    private val byType: Map<String, SourceProvider> =
        (listOf<SourceProvider>(archive) + others).flatMap { provider -> provider.types.map { it to provider } }.toMap()

    suspend fun fetch(entry: CatalogEntry, cache: Boolean = true): ArchiveItem =
        (byType[entry.sourceType] ?: throw UnsupportedSourceException(entry.sourceType)).fetch(entry, cache)

    /** Files of web sources carry their own address; Internet Archive addresses are built from item and path. */
    fun downloadUrl(file: RomFile): String = file.url ?: archive.downloadUrl(file)
}
