package app.archivefetcher.data

/**
 * One row of a game list: a single file, or every disc of a multi-disc release folded together so it is picked,
 * downloaded and saved for later as one game.
 */
data class GameEntry(
    /** What the row shows: the file name, or the release name without the disc tag. */
    val title: String,
    val files: List<RomFile>,
) {
    val key: String = if (files.size == 1) files[0].key else "discs:" + files.joinToString("|") { it.key }
    val isMultiDisc: Boolean get() = files.size > 1
    val totalSize: Long get() = files.sumOf { it.size }
    val first: RomFile get() = files[0]
}

object GameEntries {
    /**
     * Keeps the order of [files] (already sorted by name) and folds the discs of a release into the position of its
     * first disc. Discs only fold together when they come from the same collection and share a format, so a game
     * offered both as .chd and as .zip stays two choices.
     */
    fun group(files: List<RomFile>): List<GameEntry> {
        val discsOf = HashMap<Triple<String, String, String>, MutableList<RomFile>>()
        for (file in files) {
            if (!MultiDisc.hasDiscTag(file.displayName)) continue
            discsOf.getOrPut(Triple(RomVariants.releaseKey(file.displayName), file.packTitle, file.format)) { ArrayList() } += file
        }
        val emitted = HashSet<Triple<String, String, String>>()
        val entries = ArrayList<GameEntry>(files.size)
        for (file in files) {
            val group = if (MultiDisc.hasDiscTag(file.displayName)) {
                Triple(RomVariants.releaseKey(file.displayName), file.packTitle, file.format)
            } else {
                null
            }
            val discs = group?.let(discsOf::get)
            when {
                discs == null || discs.size < 2 -> entries += GameEntry(file.displayName, listOf(file))
                emitted.add(group) -> entries += GameEntry(
                    group.first,
                    discs.sortedBy { MultiDisc.discOf(it.displayName)?.number ?: 0 },
                )
            }
        }
        return entries
    }
}
