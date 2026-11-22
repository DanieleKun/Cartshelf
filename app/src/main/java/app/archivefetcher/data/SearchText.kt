package app.archivefetcher.data

import java.text.Normalizer

/**
 * Text matching for game names that forgives what people do not type: accents (Pokémon = pokemon), case,
 * punctuation and dashes (`Pokemon - Versione Argento`), word order, and words run together (`megaman` = `Mega Man`).
 */
object SearchText {
    private val marks = Regex("""\p{Mn}+""")
    private val separators = Regex("""[^\p{L}\p{Nd}]+""")

    /** Lower-case words separated by single spaces, without accents or punctuation. */
    fun normalize(text: String): String {
        val folded = text.lowercase()
            .replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("ł", "l")
        val stripped = marks.replace(Normalizer.normalize(folded, Normalizer.Form.NFD), "")
        return separators.replace(stripped, " ").trim()
    }

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()
}

/** A parsed search: every word must be found in the name, in any order. */
class SearchQuery private constructor(val tokens: List<String>) {
    val isEmpty: Boolean get() = tokens.isEmpty()

    /** Whether a typo-tolerant second pass can find something the exact pass missed. */
    val canBeFuzzy: Boolean get() = tokens.any { it.length >= FUZZY_MIN_LENGTH }

    /** [name] must already be normalized with [SearchText.normalize]. */
    fun matches(name: String): Boolean {
        if (tokens.isEmpty()) return false
        var compact: String? = null
        // Words run together ("megaman") are only tried for longer words, so "rm" does not match "supeRMario".
        return tokens.all { token ->
            name.contains(token) ||
                (token.length >= FUZZY_MIN_LENGTH && (compact ?: name.replace(" ", "").also { compact = it }).contains(token))
        }
    }

    /** Like [matches], but a long word may differ by one letter (two for long words) from a word of the name. */
    fun matchesFuzzy(name: String): Boolean {
        if (tokens.isEmpty()) return false
        var words: List<String>? = null
        return tokens.all { token ->
            if (name.contains(token)) return@all true
            if (token.length < FUZZY_MIN_LENGTH) return@all false
            val list = words ?: name.split(' ').also { words = it }
            val allowed = if (token.length >= LONG_WORD) 2 else 1
            list.any { word -> closeEnough(token, word, allowed) }
        }
    }

    private fun closeEnough(token: String, word: String, allowed: Int): Boolean {
        if (word.length + allowed < token.length) return false
        // A word typed only in part ("argen") is compared with the same length of the name's word.
        val target = if (word.length > token.length + allowed) word.substring(0, token.length) else word
        return editDistance(token, target, allowed) <= allowed
    }

    companion object {
        private const val FUZZY_MIN_LENGTH = 4
        private const val LONG_WORD = 8

        fun parse(text: String): SearchQuery = SearchQuery(SearchText.tokens(text))

        /** Levenshtein distance with transpositions, giving up early once it exceeds [limit]. */
        internal fun editDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prevPrev = IntArray(b.length + 1)
            var prev = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = minOf(prev[j] + 1, current[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, prevPrev[j - 2] + 1)
                    current[j] = value
                    rowMin = minOf(rowMin, value)
                }
                if (rowMin > limit) return limit + 1
                val recycled = prevPrev
                prevPrev = prev
                prev = current
                current = recycled
            }
            return prev[b.length]
        }
    }
}
