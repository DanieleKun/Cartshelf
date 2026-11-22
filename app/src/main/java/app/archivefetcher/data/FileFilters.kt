package app.archivefetcher.data

/**
 * Multi-select filters: values inside one group are alternatives (any match),
 * while different groups all have to match. Regions and languages are the short grouped lists of [RomNaming].
 */
data class FileFilters(
    val regions: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val formats: Set<String> = emptySet(),
    val packs: Set<String> = emptySet(),
    /** Leaves out files that are already in the destination folder. */
    val hidePresent: Boolean = false,
) {
    val activeCount: Int get() = regions.size + languages.size + formats.size + packs.size + if (hidePresent) 1 else 0

    fun matches(file: RomFile): Boolean =
        (regions.isEmpty() || file.regionGroups.any { it in regions }) &&
            (languages.isEmpty() || file.languageGroups.any { it in languages }) &&
            (formats.isEmpty() || file.format in formats) &&
            (packs.isEmpty() || file.packTitle in packs)

    fun toggleRegion(value: String) = copy(regions = regions.toggled(value))
    fun toggleLanguage(value: String) = copy(languages = languages.toggled(value))
    fun toggleFormat(value: String) = copy(formats = formats.toggled(value))
    fun togglePack(value: String) = copy(packs = packs.toggled(value))
    fun withHidePresent(value: Boolean) = copy(hidePresent = value)

    private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
}
