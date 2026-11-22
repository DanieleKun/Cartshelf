package app.archivefetcher.download

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.archivefetcher.data.ExtractionSink
import java.io.IOException
import java.io.OutputStream

/**
 * Writes extracted entries under a SAF tree. Files are written under a temporary name and only take their real name in
 * [commit], so a paused or failed extraction never replaces a file the user already had.
 */
class SafExtractionSink(
    private val context: Context,
    private val root: DocumentFile,
) : ExtractionSink {
    private class Pending(val parent: DocumentFile, val file: DocumentFile, val name: String)

    private val createdDirs = ArrayList<DocumentFile>()
    private val pending = ArrayList<Pending>()

    // Each SAF query is slow, so a folder is listed once and then kept up to date locally.
    private val listings = HashMap<String, MutableMap<String, DocumentFile>>()

    override fun makeDirectory(path: List<String>) {
        directory(path)
    }

    override fun openFile(path: List<String>): OutputStream {
        val parent = directory(path.dropLast(1))
        val name = path.last()
        val tempName = name + TEMP_SUFFIX
        children(parent).remove(tempName)?.delete()
        val file = parent.createFile("application/octet-stream", tempName)
            ?: throw IOException("Couldn't create $name")
        pending += Pending(parent, file, name)
        return context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("Couldn't write $name")
    }

    /** Gives every extracted file its real name, replacing an older copy only now that the archive was read fully. */
    fun commit() {
        for (item in pending) {
            val siblings = children(item.parent)
            siblings.remove(item.name)?.delete()
            if (!item.file.renameTo(item.name)) throw IOException("Couldn't rename ${item.name}")
            siblings[item.name] = item.file
        }
        pending.clear()
        createdDirs.clear()
    }

    /** Deletes the temporary files and the folders this sink created; best effort, after a failure or cancellation. */
    fun rollback() {
        pending.forEach { runCatching { it.file.delete() } }
        createdDirs.asReversed().forEach { runCatching { it.delete() } }
        pending.clear()
        createdDirs.clear()
        listings.clear()
    }

    private fun children(dir: DocumentFile): MutableMap<String, DocumentFile> =
        listings.getOrPut(dir.uri.toString()) {
            dir.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap(HashMap())
        }

    private fun directory(path: List<String>): DocumentFile =
        path.fold(root) { parent, part ->
            val siblings = children(parent)
            siblings[part]?.takeIf { it.isDirectory }
                ?: parent.createDirectory(part)?.also {
                    siblings[part] = it
                    createdDirs += it
                }
                ?: throw IOException("Couldn't create folder $part")
        }

    private companion object {
        /** Ends in ".part" so the folder scan never mistakes a half-written file for a finished one. */
        const val TEMP_SUFFIX = ".extract.part"
    }
}
