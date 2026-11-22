package app.archivefetcher.data

/** One file stored inside a zip that lives in an Internet Archive item. */
data class ZipMember(val name: String, val size: Long)

/**
 * Reads the directory page Internet Archive serves for a zip file
 * (`https://archive.org/download/<item>/<file>.zip/`), which lists every member with its size.
 * Individual members can then be downloaded without fetching the whole zip.
 */
object ZipListing {
    private val row = Regex("""<tr><td><a href="[^"]*">([^<]+)</a><td><td>[^<]*<td id="size">(\d*)</tr>""")

    fun parse(html: String): List<ZipMember> =
        row.findAll(html).mapNotNull { match ->
            val name = unescape(match.groupValues[1])
            val size = match.groupValues[2].toLongOrNull()
            if (size == null || !isSafeMemberName(name)) null else ZipMember(name, size)
        }.toList()

    private fun isSafeMemberName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 300 && !name.startsWith("/") && '\\' !in name && '\u0000' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&#39;", "'").replace("&amp;", "&")

    /** Upper bound for a listing page; the largest zips seen list ~7000 members in about 2 MB. */
    const val MAX_BYTES = 32L * 1024 * 1024
}
