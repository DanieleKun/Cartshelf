package app.archivefetcher.data

/** Derives region, language and format from file names that follow the No-Intro / Redump convention. */
object RomNaming {
    private val knownRegions = setOf(
        "USA", "Europe", "Japan", "World", "Asia", "Australia", "Korea", "Brazil", "Canada",
        "China", "France", "Germany", "Italy", "Spain", "Netherlands", "Sweden", "Denmark",
        "Finland", "Norway", "Russia", "Taiwan", "UK", "Hong Kong", "Greece", "Poland",
        "Portugal", "Mexico", "Scandinavia", "Latin America", "Unknown",
    )
    private val parens = Regex("""\(([^)]*)\)""")
    private val languageTag = Regex("""^[A-Za-z]{2}(-[A-Za-z]{2,4})?$""")

    /** Region groups offered by the filters. Single countries are folded into their group to keep the list short. */
    const val GROUP_USA = "USA"
    const val GROUP_EUROPE = "Europe"
    const val GROUP_JAPAN = "Japan"
    const val GROUP_WORLD = "World"
    const val GROUP_ASIA = "Asia"
    const val GROUP_OTHER = "Other"
    val regionGroups = listOf(GROUP_USA, GROUP_EUROPE, GROUP_JAPAN, GROUP_WORLD, GROUP_ASIA, GROUP_OTHER)

    private val europeanRegions = setOf(
        "Europe", "Germany", "France", "Italy", "Spain", "UK", "Netherlands", "Sweden", "Denmark", "Finland",
        "Norway", "Portugal", "Greece", "Poland", "Russia", "Scandinavia",
    )
    private val asianRegions = setOf("Asia", "China", "Korea", "Taiwan", "Hong Kong")

    /** Languages with their own filter option; every other real language is grouped under [LANGUAGE_OTHER]. */
    val mainLanguages = listOf("En", "Fr", "De", "Es", "It", "Ja", "Pt", "Nl")
    const val LANGUAGE_OTHER = "Other"

    /** Real ISO 639-1 codes seen in No-Intro / Redump names; anything else in parentheses is not a language. */
    private val knownLanguages = mainLanguages.toSet() + setOf(
        "Ko", "Zh", "Ru", "Sv", "Da", "No", "Fi", "Pl", "Cs", "Hu", "El", "Tr", "Ar", "Th", "Sk", "Sl", "Hr",
        "He", "Hi", "Ro", "Bg", "Ca",
    )
    private val languageAliases = mapOf("Jp" to "Ja", "Kr" to "Ko", "Cz" to "Cs")
    private val titleFormat = Regex("""(?:^|\s)\.([A-Za-z0-9]{2,5})\b""")

    private val unsafeNameChars = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private const val MAX_NAME_LENGTH = 200

    /** Replaces characters that are unsafe in file names and rejects blank, "." and ".." names. */
    fun safeFileName(name: String): String {
        val cleaned = name.replace(unsafeNameChars, "_").trim().take(MAX_NAME_LENGTH)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "download" else cleaned
    }

    private fun groups(fileName: String): List<List<String>> =
        parens.findAll(fileName)
            .map { m -> m.groupValues[1].split(',').map(String::trim).filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }
            .toList()

    /** Short region codes of GoodTools-style names, such as `(U)`, `(E)`, `(JUE)` or `(I)`. */
    private val goodToolsRegions = mapOf(
        'U' to "USA", 'E' to "Europe", 'J' to "Japan", 'W' to "World", 'I' to "Italy", 'F' to "France",
        'G' to "Germany", 'S' to "Spain", 'B' to "Brazil", 'K' to "Korea", 'C' to "China", 'A' to "Australia",
    )
    private val goodToolsCode = Regex("^[UEJ]{2,3}$|^[UEJWIFGSBKCA]$")

    fun regionsOf(fileName: String): List<String> =
        groups(fileName).mapNotNull { g ->
            when {
                g.all { it in knownRegions } -> g
                g.size == 1 && goodToolsCode.matches(g[0]) -> g[0].map { goodToolsRegions.getValue(it) }
                else -> null
            }
        }.flatten().distinct()

    /** Language codes such as "En" or "It", taken from groups like "(En,Fr,De)"; unknown codes are ignored. */
    fun languagesOf(fileName: String): List<String> =
        groups(fileName)
            .filter { g -> g.none { it in knownRegions } && g.all { languageTag.matches(it) } }
            .map { g -> g.map(::normalizeLanguage) }
            .filter { g -> g.all { it in knownLanguages } }
            .flatten()
            .distinct()

    private fun normalizeLanguage(code: String): String {
        val base = code.substringBefore('-')
        val name = base.substring(0, 1).uppercase() + base.substring(1).lowercase()
        return languageAliases[name] ?: name
    }

    /** Folds raw regions (e.g. "Germany", "Korea") into the few groups the filters offer. */
    fun regionGroupsOf(regions: List<String>): List<String> =
        regions.map { region ->
            when {
                region == "USA" || region == "Canada" -> GROUP_USA
                region == "Japan" -> GROUP_JAPAN
                region == "World" -> GROUP_WORLD
                region in europeanRegions -> GROUP_EUROPE
                region in asianRegions -> GROUP_ASIA
                else -> GROUP_OTHER
            }
        }.distinct()

    fun languageGroupsOf(languages: List<String>): List<String> =
        languages.map { if (it in mainLanguages) it else LANGUAGE_OTHER }.distinct()

    /** The name without its extension, so `Game (USA).zip` and an unpacked `Game (USA).gba` count as the same game. */
    fun stemOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 5 && !ext.contains(' ')) fileName.dropLast(ext.length + 1) else fileName
    }

    /** Prefers the format announced by the collection title (e.g. ".iso") over the archive extension. */
    fun formatOf(fileName: String, packTitle: String = ""): String {
        titleFormat.find(packTitle)?.let { return it.groupValues[1].lowercase() }
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isBlank() || ext.length > 5) "?" else ext.lowercase()
    }
}
