package app.archivefetcher.data

/** The kinds of place a collection can point to; see the addon specification, section 4. */
object SourceTypes {
    /** An item on the Internet Archive (optionally a folder or a file in it). */
    const val ARCHIVE_ITEM = "archive.org-item"

    /** A web page that lists the files of a folder, as most web servers generate (an "index of" page). */
    const val HTTP_DIRECTORY = "http-directory"

    /** A JSON document, in the format of the specification, that lists names, addresses and sizes. */
    const val FILE_LIST = "file-list"

    /** A list of files written directly in the addon. */
    const val FILES = "files"

    val all = setOf(ARCHIVE_ITEM, HTTP_DIRECTORY, FILE_LIST, FILES)
}

/** A file anywhere on the web, as listed by an addon, a feed or a directory page. */
data class RemoteFile(val name: String, val url: String, val size: Long = 0, val md5: String? = null)

/**
 * One collection of a console. [identifier] names the source: the Internet Archive item, or the address of the
 * directory page or feed; inline lists use a stable key made from the addon and the title.
 */
data class CatalogEntry(
    val title: String,
    val identifier: String,
    val subPath: String = "",
    /** File type declared by the addon (e.g. "chd"); when null it is derived from the title or the file name. */
    val format: String? = null,
    /** True when the files of the collection are zips whose members should be listed and downloaded one by one. */
    val expandZip: Boolean = false,
    val sourceType: String = SourceTypes.ARCHIVE_ITEM,
    /** For directory pages: which file extensions are part of the collection. */
    val extensions: List<String> = emptyList(),
    /** For inline lists: the files themselves. */
    val files: List<RemoteFile> = emptyList(),
)

/** A downloadable file of a collection, from any kind of source. */
data class RomFile(
    val identifier: String,
    val path: String,
    val size: Long,
    val regions: List<String>,
    val languages: List<String>,
    val format: String,
    val packTitle: String,
    /** True when the host requires a logged-in account to download it (Internet Archive only). */
    val locked: Boolean,
    /** MD5 published by the source, when it publishes one. */
    val md5: String? = null,
    /** Direct download address for sources other than the Internet Archive, whose address is built from the item. */
    val url: String? = null,
) {
    /** Safe to use as a local file name even though it comes from remote metadata. */
    val displayName: String = RomNaming.safeFileName(path.substringAfterLast('/'))
    val key: String = "$identifier/$path"

    /** The name reduced for matching: no accents, punctuation or case (see [SearchText]). */
    val searchName: String = SearchText.normalize(displayName)
    val regionGroups: List<String> = RomNaming.regionGroupsOf(regions)
    val languageGroups: List<String> = RomNaming.languageGroupsOf(languages)

    /** Beta/demo or pirate/hack markers, read once from the name. */
    val flags: Set<RomVariants.Flag> by lazy(LazyThreadSafetyMode.PUBLICATION) { RomVariants.flagsOf(displayName) }

    companion object {
        /** Builds a file from stored fields, deriving regions and languages from its name. */
        fun create(
            identifier: String,
            path: String,
            size: Long,
            format: String,
            packTitle: String,
            locked: Boolean,
            md5: String?,
            url: String? = null,
        ): RomFile {
            val shortName = path.substringAfterLast('/')
            val regions = RomNaming.regionsOf(shortName)
            return RomFile(
                identifier, path, size, regions, RomLanguages.of(shortName, regions),
                format, packTitle, locked, md5, url,
            )
        }
    }
}

/** What a source listed for one collection. The name is historical: every source type produces one. */
data class ArchiveItem(
    val identifier: String,
    val locked: Boolean,
    val files: List<RomFile>,
)
