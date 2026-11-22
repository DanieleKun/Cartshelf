package app.archivefetcher.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IndexHit(val consoleId: String, val file: RomFile)

/**
 * [total] counts every match, [hits] only the first ones that were asked for.
 * [approximate] is true when nothing matched exactly and the results allow small typos.
 */
data class SearchResult(val hits: List<IndexHit>, val total: Int, val approximate: Boolean = false)

/**
 * Local copy of the file names of each console, so a search across all consoles needs no network.
 * Each console has two files with one line per file, in the same order: `<id>.names` holds only the normalized
 * names (see [SearchText]) and is what a search scans; `<id>.tsv` holds the full rows (name, item, path, size, md5,
 * locked, format, collection, direct url) and is read only for the matches. A `format` file records the layout version, so an
 * index written by an older version of the app is dropped instead of being misread.
 */
class IndexStore(private val dir: File) {
    private val _indexed = MutableStateFlow(openIds())

    /** Ids of the consoles that have an index. */
    val indexed: StateFlow<Set<String>> = _indexed.asStateFlow()

    private val writeLock = Any()

    fun write(consoleId: String, files: List<RomFile>) = synchronized(writeLock) {
        requireSafe(consoleId)
        dir.mkdirs()
        writeVersion()
        // Rows first: the names file is what marks the console as indexed.
        writeAtomically(rowsFile(consoleId)) { out ->
            for (file in files) {
                out.write(
                    listOf(
                        file.searchName, clean(file.identifier), clean(file.path), file.size.toString(),
                        file.md5.orEmpty(), if (file.locked) "1" else "0", clean(file.format), clean(file.packTitle), clean(file.url.orEmpty()),
                    ).joinToString("\t"),
                )
                out.write("\n")
            }
        }
        writeAtomically(namesFile(consoleId)) { out ->
            for (file in files) {
                out.write(file.searchName)
                out.write("\n")
            }
        }
        _indexed.update { it + consoleId }
    }

    fun clear() = synchronized(writeLock) {
        dir.listFiles()?.forEach { it.delete() }
        _indexed.value = emptySet()
    }

    /**
     * Files whose name contains every word of [query], in any order. When nothing matches exactly, a second pass
     * allows small typos. Consoles are scanned in [order], restricted to [only] when given. [checkCancelled] is called
     * regularly so a newer query can stop an older scan.
     */
    fun search(
        query: String,
        limit: Int,
        order: List<String>,
        only: Set<String>? = null,
        checkCancelled: () -> Unit = {},
    ): SearchResult {
        val parsed = SearchQuery.parse(query)
        if (parsed.isEmpty) return SearchResult(emptyList(), 0)
        val exact = scan(order, only, limit, checkCancelled) { parsed.matches(it) }
        if (exact.total > 0 || !parsed.canBeFuzzy) return exact
        return scan(order, only, limit, checkCancelled) { parsed.matchesFuzzy(it) }.copy(approximate = true)
    }

    private inline fun scan(
        order: List<String>,
        only: Set<String>?,
        limit: Int,
        checkCancelled: () -> Unit,
        matches: (String) -> Boolean,
    ): SearchResult {
        val hits = ArrayList<IndexHit>()
        var total = 0
        val have = _indexed.value
        for (consoleId in order) {
            if (consoleId !in have || (only != null && consoleId !in only)) continue
            val names = namesFile(consoleId)
            if (!names.exists()) continue
            val wanted = ArrayList<Pair<Int, String>>()
            var lineNo = 0
            names.bufferedReader().useLines { lines ->
                for (name in lines) {
                    if (lineNo % CANCEL_CHECK_EVERY == 0) checkCancelled()
                    if (matches(name)) {
                        total++
                        if (hits.size + wanted.size < limit) wanted += lineNo to name
                    }
                    lineNo++
                }
            }
            if (wanted.isNotEmpty()) hits += readRows(consoleId, wanted)
        }
        return SearchResult(hits, total)
    }

    /**
     * Reads the rows at the given (ascending) line numbers, stopping after the last one. A row whose name differs from
     * the matched one belongs to an index being rewritten right now and is skipped rather than shown as the wrong file.
     */
    private fun readRows(consoleId: String, wanted: List<Pair<Int, String>>): List<IndexHit> {
        val rows = rowsFile(consoleId)
        if (!rows.exists()) return emptyList()
        val result = ArrayList<IndexHit>(wanted.size)
        var next = 0
        rows.bufferedReader().useLines { lines ->
            for ((index, line) in lines.withIndex()) {
                if (next >= wanted.size) break
                val (lineNo, name) = wanted[next]
                if (index != lineNo) continue
                if (line.startsWith(name + "\t")) parse(consoleId, line)?.let { result += it }
                next++
            }
        }
        return result
    }

    private fun parse(consoleId: String, line: String): IndexHit? {
        val parts = line.split('\t', limit = 9)
        if (parts.size < 9) return null
        val size = parts[3].toLongOrNull() ?: return null
        val file = RomFile.create(
            identifier = parts[1], path = parts[2], size = size, format = parts[6], packTitle = parts[7],
            locked = parts[5] == "1", md5 = parts[4].takeIf { it.isNotBlank() }, url = parts[8].takeIf { it.isNotBlank() },
        )
        return IndexHit(consoleId, file)
    }

    private fun writeAtomically(target: File, block: (java.io.Writer) -> Unit) {
        val tmp = File.createTempFile(target.name, ".tmp", dir)
        tmp.bufferedWriter().use(block)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun namesFile(consoleId: String) = File(dir, "$consoleId$NAMES")

    private fun rowsFile(consoleId: String) = File(dir, "$consoleId$ROWS")

    private fun versionFile() = File(dir, VERSION_FILE)

    private fun writeVersion() {
        if (versionFile().exists()) return
        versionFile().writeText(VERSION.toString())
    }

    /** Ids of the indexed consoles; an index in an older layout is removed so it gets rebuilt. */
    private fun openIds(): Set<String> {
        val files = dir.listFiles() ?: return emptySet()
        if (files.isEmpty()) return emptySet()
        val version = runCatching { versionFile().readText().trim().toInt() }.getOrNull()
        if (version != VERSION) {
            files.forEach { it.delete() }
            return emptySet()
        }
        return files.filter { it.name.endsWith(NAMES) }.map { it.name.removeSuffix(NAMES) }
            .filter { rowsFile(it).exists() }.toSet()
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun requireSafe(consoleId: String) =
        require(consoleId.isNotEmpty() && consoleId.all { it.isLetterOrDigit() || it == '-' }) { "unsafe console id" }

    private companion object {
        const val NAMES = ".names"
        const val ROWS = ".tsv"
        const val VERSION_FILE = "format"
        const val VERSION = 3
        const val CANCEL_CHECK_EVERY = 4000
    }
}
