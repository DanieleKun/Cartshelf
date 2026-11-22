package app.archivefetcher.data

/**
 * Works out which languages a game file is in, from everything its name says.
 *
 * Sources, in order of trust:
 * 1. An explicit language list in parentheses, as in No-Intro / Redump: `(En,Fr,De,Es,It)`.
 * 2. Language words or tags in brackets or parentheses: `(Italian)`, `[ITA]`, `[T-Ita]`, `(It)`.
 * 3. Upper-case scene tags or full language names outside brackets: `ITA`, `Italiano`.
 * 4. When nothing explicit is found, the language implied by the release region: `(Italy)` → Italian,
 *    `(USA)` → English, GoodTools `(I)` → Italian.
 *
 * The result uses the two-letter codes of [RomNaming] (En, Fr, De, Es, It, Ja, Pt, Nl and the minor ones).
 */
object RomLanguages {
    private val bracketed = Regex("""[(\[]([^)\]]*)[)\]]""")
    private val wordSplit = Regex("""[\s,;+/&_.-]+""")
    private val upperWord = Regex("""(?<![A-Za-z])([A-Z]{3})(?![A-Za-z])""")
    private val translation = Regex("""^T[+-]([A-Za-z]{2,10})""")

    /** Full names and common abbreviations, lower-case, in English and in the language itself. */
    private val words: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(it, code) }
        add("En", "english", "eng", "inglese", "anglais", "englisch", "ingles", "inglés")
        add("Fr", "french", "fre", "fra", "francais", "français", "francese", "französisch", "frances", "francés")
        add("De", "german", "ger", "deu", "deutsch", "tedesco", "allemand", "aleman", "alemán")
        add("Es", "spanish", "spa", "esp", "espanol", "español", "castellano", "spagnolo", "espagnol", "spanisch")
        add("It", "italian", "ita", "italiano", "italien", "italienisch")
        add("Ja", "japanese", "jpn", "jap", "giapponese", "japonais", "japanisch", "japones", "japonés")
        add("Pt", "portuguese", "por", "portugues", "português", "portoghese", "portugais", "brazilian")
        add("Nl", "dutch", "dut", "nld", "nederlands", "olandese", "néerlandais")
        add("Ko", "korean", "kor", "coreano", "coréen")
        add("Zh", "chinese", "chi", "zho", "chs", "cht", "cinese", "chinois")
        add("Ru", "russian", "rus", "russo", "russe", "russisch")
        add("Sv", "swedish", "swe", "svenska", "svedese")
        add("Da", "danish", "dan", "dansk", "danese")
        add("No", "norwegian", "nor", "norsk", "norvegese")
        add("Fi", "finnish", "fin", "suomi", "finlandese")
        add("Pl", "polish", "pol", "polski", "polacco")
        add("Cs", "czech", "cze", "ces", "čeština", "ceco")
        add("Hu", "hungarian", "hun", "magyar", "ungherese")
        add("El", "greek", "gre", "ell", "greco")
        add("Tr", "turkish", "tur", "türkçe", "turco")
        add("Ar", "arabic", "ara", "arabo")
        add("Ca", "catalan", "català", "catalano")
    }

    /** Scene-style upper-case tags that are safe to read outside brackets (`Pokemon Rosso ITA.gba`). */
    private val upperTags = setOf("ENG", "ITA", "FRE", "FRA", "GER", "DEU", "SPA", "ESP", "JPN", "POR", "DUT", "NLD", "KOR", "RUS", "SWE", "POL")

    /** Language implied by a release region when the name lists no language. */
    private val regionLanguage = mapOf(
        "USA" to "En", "UK" to "En", "Australia" to "En", "Canada" to "En", "Europe" to "En", "World" to "En",
        "Italy" to "It", "France" to "Fr", "Germany" to "De", "Spain" to "Es", "Mexico" to "Es", "Latin America" to "Es",
        "Japan" to "Ja", "Brazil" to "Pt", "Portugal" to "Pt", "Netherlands" to "Nl", "Korea" to "Ko",
        "China" to "Zh", "Taiwan" to "Zh", "Hong Kong" to "Zh", "Russia" to "Ru", "Sweden" to "Sv", "Denmark" to "Da",
        "Norway" to "No", "Finland" to "Fi", "Poland" to "Pl", "Greece" to "El",
    )

    /** Languages of a file name, with [regions] already read from it by [RomNaming.regionsOf]. */
    fun of(fileName: String, regions: List<String>): List<String> {
        val found = (RomNaming.languagesOf(fileName) + taggedLanguages(fileName)).distinct()
        if (found.isNotEmpty()) return found
        return regions.mapNotNull { regionLanguage[it] }.distinct()
    }

    private fun taggedLanguages(fileName: String): List<String> {
        val result = LinkedHashSet<String>()
        for (match in bracketed.findAll(fileName)) {
            val inside = match.groupValues[1].trim()
            // Fan translations: [T-Ita], [T+Eng], [T-Ita_v1.2 by …]
            val translated = translation.find(inside)
            if (translated != null) {
                lookup(translated.groupValues[1])?.let(result::add)
                continue
            }
            inside.split(wordSplit).forEach { word -> lookup(word)?.let(result::add) }
        }
        val outside = bracketed.replace(fileName, " ")
        upperWord.findAll(outside).forEach { match ->
            val tag = match.groupValues[1]
            if (tag in upperTags) lookup(tag)?.let(result::add)
        }
        // Only full names outside brackets: short codes there are too often part of a title.
        outside.split(wordSplit).forEach { word ->
            if (word.length >= MIN_FREE_WORD) words[word.lowercase()]?.let(result::add)
        }
        return result.toList()
    }

    private fun lookup(word: String): String? = word.trim().lowercase().takeIf { it.isNotEmpty() }?.let(words::get)

    private const val MIN_FREE_WORD = 6
}
