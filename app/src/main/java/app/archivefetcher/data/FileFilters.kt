package app.archivefetcher.data

/**
 * Multi-select filters: values inside one group are alternatives (any match),
 * while different groups all have to match. Regions and languages are the short grouped lists of [RomNaming].
 * The three quick switches ([oneGamePerTitle], [hideUnfinished], [hideUnofficial]) are remembered between consoles.
 */
data class FileFilters(
    val regions: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val formats: Set<String> = emptySet(),
    val packs: Set<String> = emptySet(),
    /** Leaves out files that are already in the destination folder. */
    val hidePresent: Boolean = false,
    /** One version per game (1G1R): the best release for the user's language; applied to the whole list. */
    val oneGamePerTitle: Boolean = false,
    /** Leaves out betas, prototypes, demos and samples. */
    val hideUnfinished: Boolean = false,
    /** Leaves out pirate, hacked, unlicensed and bad-dump releases. */
    val hideUnofficial: Boolean = false,
) {
    val activeCount: Int
        get() = regions.size + languages.size + formats.size + packs.size +
            listOf(hidePresent, oneGamePerTitle, hideUnfinished, hideUnofficial).count { it }

    /** The detailed choices, without the quick switches: what "clear filters" resets. */
    fun cleared(): FileFilters = FileFilters(oneGamePerTitle = oneGamePerTitle, hideUnfinished = hideUnfinished, hideUnofficial = hideUnofficial)

    fun matches(file: RomFile): Boolean =
        (regions.isEmpty() || file.regionGroups.any { it in regions }) &&
            (languages.isEmpty() || file.languageGroups.any { it in languages }) &&
            (formats.isEmpty() || file.format in formats) &&
            (packs.isEmpty() || file.packTitle in packs) &&
            !(hideUnfinished && RomVariants.Flag.Unfinished in file.flags) &&
            !(hideUnofficial && RomVariants.Flag.Unofficial in file.flags)

    fun toggleRegion(value: String) = copy(regions = regions.toggled(value))
    fun toggleLanguage(value: String) = copy(languages = languages.toggled(value))
    fun toggleFormat(value: String) = copy(formats = formats.toggled(value))
    fun togglePack(value: String) = copy(packs = packs.toggled(value))
    fun withHidePresent(value: Boolean) = copy(hidePresent = value)

    private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
}
