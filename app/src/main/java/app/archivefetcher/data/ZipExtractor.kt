package app.archivefetcher.data

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** Where extracted entries go; implemented on top of the Storage Access Framework in the app. */
interface ExtractionSink {
    fun makeDirectory(path: List<String>)
    fun openFile(path: List<String>): OutputStream
}

/** Counts the bytes consumed from the underlying stream, used to report extraction progress. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}

/** Streams a ZIP archive into a sink without ever needing random access to the file. */
class ZipExtractor {
    /**
     * Returns the relative paths of the extracted files. [checkCancelled] is called often and may throw
     * to abort; [onChunk] is called after every written chunk.
     */
    fun extract(
        input: InputStream,
        sink: ExtractionSink,
        checkCancelled: () -> Unit = {},
        onChunk: () -> Unit = {},
    ): List<String> {
        val extracted = ArrayList<String>()
        val buffer = ByteArray(BUFFER_SIZE)
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            var entries = 0
            while (entry != null) {
                checkCancelled()
                if (++entries > MAX_ENTRIES) throw IOException("Too many entries in archive")
                val path = safePath(entry.name)
                if (path.isNotEmpty()) {
                    if (entry.isDirectory) {
                        sink.makeDirectory(path)
                    } else {
                        sink.openFile(path).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                checkCancelled()
                                onChunk()
                            }
                        }
                        extracted += path.joinToString("/")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return extracted
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ENTRIES = 100_000

        /** Splits an entry name into safe path parts; refuses anything that could escape the destination. */
        fun safePath(name: String): List<String> {
            val parts = name.split('/', '\\').filter { it.isNotBlank() && it != "." }
            if (parts.any { it == ".." }) throw IOException("Unsafe path in archive: $name")
            return parts.map(RomNaming::safeFileName)
        }
    }
}
