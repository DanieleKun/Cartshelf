package app.archivefetcher.data

/**
 * Tells the versions of the same game apart, so a list can show one entry per game ("1G1R": one game, one ROM).
 * Works on No-Intro / Redump / GoodTools style names: `Game (Europe) (En,Fr,It) (Rev 1).zip`.
 */
object RomVariants {
    enum class Flag { Unfinished, Unofficial }

    private val bracketGroup = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val unfinishedWords = setOf(
        "beta", "proto", "prototype", "demo", "sample", "preview", "alpha", "kiosk", "promo", "debug", "trial",
        "taikenban", "test program",
    )
    private val unofficialWords = setOf("pirate", "hack", "bootleg", "unl", "unlicensed", "aftermarket")
    /** GoodTools bad dump, hack, pirate, overdump; alternates `[a]` and fixes `[f]` are good dumps and stay. */
    private val goodToolsBad = Regex("""\[(?:b|h|p|o)\d*[^\]]*]""")
    private val revision = Regex("""\((?:Rev|v)\s*([0-9A-Z.]+)\)""", RegexOption.IGNORE_CASE)

    /** Region preference by language: releases from the first regions win. */
    private val regionOrder = mapOf(
        "It" to listOf("Italy", "Europe", "World", "USA", "UK", "Australia", "Japan"),
        "En" to listOf("USA", "World", "Europe", "UK", "Australia", "Canada", "Japan"),
        "Fr" to listOf("France", "Europe", "World", "Canada", "USA", "Japan"),
        "De" to listOf("Germany", "Europe", "World", "USA", "Japan"),
        "Es" to listOf("Spain", "Europe", "Latin America", "Mexico", "World", "USA", "Japan"),
        "Pt" to listOf("Brazil", "Portugal", "Europe", "World", "USA", "Japan"),
        "Nl" to listOf("Netherlands", "Europe", "World", "USA", "Japan"),
        "Ja" to listOf("Japan", "Asia", "World", "USA", "Europe"),
    )
    private val defaultOrder = listOf("World", "Europe", "USA", "Japan")

    /** The language of a single-country release, for names that carry no language tag: `Game (USA)` is English. */
    private val regionLanguage = mapOf(
        "USA" to "En", "UK" to "En", "Australia" to "En", "Japan" to "Ja", "Italy" to "It", "France" to "Fr",
        "Germany" to "De", "Spain" to "Es", "Brazil" to "Pt", "Portugal" to "Pt", "Netherlands" to "Nl",
    )

    fun flagsOf(fileName: String): Set<Flag> {
        val stem = RomNaming.stemOf(fileName)
        val words = bracketGroup.findAll(stem)
            .flatMap { it.value.trim().trim('(', ')', '[', ']').split(',').map { word -> word.trim().lowercase() } }
            .toList()
        val flags = HashSet<Flag>()
        if (words.any { word -> unfinishedWords.any { word == it || word.startsWith("$it ") } }) flags += Flag.Unfinished
        if (words.any { it in unofficialWords } || goodToolsBad.containsMatchIn(stem)) flags += Flag.Unofficial
        return flags
    }

    /** The game's name without any tag, normalized: every version of a game shares it. */
    fun titleKey(fileName: String): String =
        SearchText.normalize(bracketGroup.replace(MultiDisc.withoutDiscTag(RomNaming.stemOf(fileName)), ""))

    /** The exact release, ignoring only the disc tag: the discs of one release share it. */
    fun releaseKey(fileName: String): String = MultiDisc.withoutDiscTag(RomNaming.stemOf(fileName))

    /**
     * One release per game: the best by [preferredLanguage] (a code such as "It"), then region, then revision.
     * Every file of the chosen release is kept: all its discs, and the same release in several formats.
     */
    fun bestPerTitle(files: List<RomFile>, preferredLanguage: String): List<RomFile> {
        if (files.isEmpty()) return files
        val order = regionOrder[preferredLanguage] ?: defaultOrder
        val chosen = files.groupBy { titleKey(it.displayName) }.values.mapTo(HashSet()) { versions ->
            versions.maxWith(
                compareBy<RomFile> { score(it, preferredLanguage, order) }
                    .thenComparator { a, b -> compareVersions(revisionOf(a.displayName), revisionOf(b.displayName)) }
                    .thenByDescending { it.displayName.length }
                    .thenByDescending { it.displayName },
            ).let { releaseKey(it.displayName) to titleKey(it.displayName) }
        }
        return files.filter { (releaseKey(it.displayName) to titleKey(it.displayName)) in chosen }
    }

    private fun score(file: RomFile, language: String, order: List<String>): Int {
        var score = 0
        val flags = flagsOf(file.displayName)
        if (Flag.Unfinished in flags) score -= 5_000
        if (Flag.Unofficial in flags) score -= 3_000
        val languages = file.languages.ifEmpty { file.regions.mapNotNull(regionLanguage::get) }
        if (language in languages) score += 1_000
        val region = file.regions.minOfOrNull { order.indexOf(it).let { i -> if (i < 0) order.size else i } } ?: (order.size + 1)
        score += (order.size + 2 - region) * 20
        return score
    }

    /** `(Rev 2)` → [2], `(Rev A)` → [1], `(v1.10)` → [1, 10]; empty without a revision. */
    internal fun revisionOf(fileName: String): List<Int> {
        val value = revision.find(fileName)?.groupValues?.get(1) ?: return emptyList()
        if (value.length == 1 && value[0].isLetter()) return listOf(value[0].uppercaseChar() - 'A' + 1)
        return value.split('.').map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /** Part by part, so v1.10 is newer than v1.9 and v2.0 newer than both. */
    internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
