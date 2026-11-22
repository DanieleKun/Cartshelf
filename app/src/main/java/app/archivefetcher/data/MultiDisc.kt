package app.archivefetcher.data

/**
 * Builds `.m3u` playlists for games that span several discs, so an emulator shows one entry and can swap discs.
 * `Final Fantasy VII (USA) (Disc 1).cue` … `(Disc 3).cue` become `Final Fantasy VII (USA).m3u` with one line per disc,
 * in disc order. Only names are handled here; reading and writing the folder is up to the caller.
 */
object MultiDisc {
    /** `(Disc 1)`, `(Disk 2 of 3)`, `(CD1)`, `[Disc 2]`, `(Disco 1)`: the disc tag of a file name. */
    private val discTag = Regex("""\s*[(\[]\s*(?:disc|disk|disco|cd)\s*(\d{1,2})(?:\s*(?:of|di|/)\s*\d{1,2})?\s*[)\]]""", RegexOption.IGNORE_CASE)

    /**
     * Formats an emulator can start a disc from, best first. A `.cue` or `.gdi` describes the whole disc and its
     * `.bin`/`.raw` tracks, so when it is present the tracks are never listed themselves.
     */
    private val diskFormats = listOf("cue", "gdi", "ccd", "mds", "chd", "iso", "cso", "pbp", "img", "bin")

    data class Disc(val game: String, val number: Int)

    data class Playlist(val fileName: String, val entries: List<String>) {
        val content: String get() = entries.joinToString("\n", postfix = "\n")
    }

    /** The game and disc number of a file name, or null when the name has no disc tag. */
    fun discOf(fileName: String): Disc? {
        val stem = RomNaming.stemOf(fileName)
        val match = discTag.find(stem) ?: return null
        val game = stem.removeRange(match.range).replace(Regex("""\s{2,}"""), " ").trim()
        if (game.isEmpty()) return null
        return Disc(game, match.groupValues[1].toInt())
    }

    fun hasDiscTag(fileName: String): Boolean = discOf(fileName) != null

    /**
     * Playlists for every game in [names] (the files of one folder) that has at least two discs. Each disc is
     * represented by its best format; a disc whose only files are unplayable (e.g. `.sbi`) is left out.
     */
    fun playlists(names: Collection<String>): List<Playlist> {
        val byGame = LinkedHashMap<String, MutableMap<Int, String>>()
        for (name in names) {
            val ext = name.substringAfterLast('.', "").lowercase()
            val rank = diskFormats.indexOf(ext)
            if (rank < 0) continue
            val disc = discOf(name) ?: continue
            val discs = byGame.getOrPut(disc.game) { HashMap() }
            val current = discs[disc.number]
            if (current == null || rank < diskFormats.indexOf(current.substringAfterLast('.').lowercase())) {
                discs[disc.number] = name
            }
        }
        return byGame.filter { it.value.size >= 2 }.map { (game, discs) ->
            Playlist(RomNaming.safeFileName("$game.m3u"), discs.toSortedMap().values.toList())
        }
    }
}
