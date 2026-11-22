package app.archivefetcher.data

/** One collection of a console: a titled pointer to an Internet Archive item (optionally a folder or a file in it). */
data class CatalogEntry(
    val title: String,
    val identifier: String,
    val subPath: String = "",
    /** File type declared by the addon (e.g. "chd"); when null it is derived from the title or the file name. */
    val format: String? = null,
    /** True when the files of the collection are zips whose members should be listed and downloaded one by one. */
    val expandZip: Boolean = false,
)

/** A downloadable file inside an Internet Archive item. */
data class RomFile(
    val identifier: String,
    val path: String,
    val size: Long,
    val regions: List<String>,
    val languages: List<String>,
    val format: String,
    val packTitle: String,
    /** True when Internet Archive requires a logged-in account to download it. */
    val locked: Boolean,
    /** MD5 published by Internet Archive; unknown for files listed from inside a zip. */
    val md5: String? = null,
) {
    /** Safe to use as a local file name even though it comes from remote metadata. */
    val displayName: String = RomNaming.safeFileName(path.substringAfterLast('/'))
    val key: String = "$identifier/$path"
    val regionGroups: List<String> = RomNaming.regionGroupsOf(regions)
    val languageGroups: List<String> = RomNaming.languageGroupsOf(languages)

    companion object {
        /** Builds a file from stored fields, deriving regions and languages from its name. */
        fun create(
            identifier: String, path: String, size: Long, format: String, packTitle: String, locked: Boolean, md5: String?,
        ): RomFile {
            val shortName = path.substringAfterLast('/')
            return RomFile(
                identifier, path, size, RomNaming.regionsOf(shortName), RomNaming.languagesOf(shortName),
                format, packTitle, locked, md5,
            )
        }
    }
}

data class ArchiveItem(
    val identifier: String,
    val locked: Boolean,
    val files: List<RomFile>,
)
