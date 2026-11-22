package app.archivefetcher.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IndexHit(val consoleId: String, val file: RomFile)

/** [total] counts every match, [hits] only the first ones that were asked for. */
data class SearchResult(val hits: List<IndexHit>, val total: Int)

/**
 * Local copy of the file names of each console, so a search across all consoles needs no network.
 * Each console has two files with one line per file, in the same order: `<id>.names` holds only the lower-case
 * names and is what a search scans; `<id>.tsv` holds the full rows (name, item, path, size, md5, locked, format,
 * collection) and is read only for the matches.
 */
class IndexStore(private val dir: File) {
    private val _indexed = MutableStateFlow(readIds())

    /** Ids of the consoles that have an index. */
    val indexed: StateFlow<Set<String>> = _indexed.asStateFlow()

    private val writeLock = Any()

    fun write(consoleId: String, files: List<RomFile>) = synchronized(writeLock) {
        requireSafe(consoleId)
        dir.mkdirs()
        // Rows first: the names file is what marks the console as indexed.
        writeAtomically(rowsFile(consoleId)) { out ->
            for (file in files) {
                out.write(
                    listOf(
                        clean(file.displayName).lowercase(), clean(file.identifier), clean(file.path), file.size.toString(),
                        file.md5.orEmpty(), if (file.locked) "1" else "0", clean(file.format), clean(file.packTitle),
                    ).joinToString("\t"),
                )
                out.write("\n")
            }
        }
        writeAtomically(namesFile(consoleId)) { out ->
            for (file in files) {
                out.write(clean(file.displayName).lowercase())
                out.write("\n")
            }
        }
        _indexed.update { it + consoleId }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        _indexed.value = emptySet()
    }

    /**
     * Files whose name contains every token (already lower-case). Consoles are scanned in [order], restricted to
     * [only] when given. [checkCancelled] is called regularly so a newer query can stop an older scan.
     */
    fun search(
        tokens: List<String>,
        limit: Int,
        order: List<String>,
        only: Set<String>? = null,
        checkCancelled: () -> Unit = {},
    ): SearchResult {
        if (tokens.isEmpty()) return SearchResult(emptyList(), 0)
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
                    if (tokens.all { name.contains(it) }) {
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
        val parts = line.split('\t', limit = 8)
        if (parts.size < 8) return null
        val size = parts[3].toLongOrNull() ?: return null
        val file = RomFile.create(
            identifier = parts[1], path = parts[2], size = size, format = parts[6], packTitle = parts[7],
            locked = parts[5] == "1", md5 = parts[4].takeIf { it.isNotBlank() },
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

    private fun readIds(): Set<String> =
        dir.listFiles()?.filter { it.name.endsWith(NAMES) }?.map { it.name.removeSuffix(NAMES) }
            ?.filter { rowsFile(it).exists() }?.toSet() ?: emptySet()

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun requireSafe(consoleId: String) =
        require(consoleId.isNotEmpty() && consoleId.all { it.isLetterOrDigit() || it == '-' }) { "unsafe console id" }

    private companion object {
        const val NAMES = ".names"
        const val ROWS = ".tsv"
        const val CANCEL_CHECK_EVERY = 4000
    }
}
