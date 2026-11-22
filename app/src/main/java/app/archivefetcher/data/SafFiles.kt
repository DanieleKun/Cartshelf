package app.archivefetcher.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object SafFiles {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * Finds a direct child of [dir] by exact name. `DocumentFile.findFile` lists the whole folder, which takes seconds
     * in a folder with thousands of games; the device and SD card provider uses path-like document ids, so there the
     * child can be addressed directly. Other providers fall back to the listing.
     */
    fun findChild(context: Context, dir: DocumentFile, name: String): DocumentFile? {
        val parentId = runCatching { DocumentsContract.getDocumentId(dir.uri) }.getOrNull()
        if (parentId == null || dir.uri.authority != EXTERNAL_STORAGE) return dir.findFile(name)
        val childId = if (parentId.endsWith(":")) parentId + name else "$parentId/$name"
        val child = DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(dir.uri, childId))
        return child?.takeIf { it.exists() }
    }

    /** Names of the files directly inside [dir], read with one query instead of one call per file. */
    fun childNames(context: Context, dir: DocumentFile): List<String> {
        val parentId = runCatching { DocumentsContract.getDocumentId(dir.uri) }.getOrNull() ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val names = ArrayList<String>()
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                if (cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) names += name
            }
        }
        return names
    }

    /** Creates or replaces a small text file; an unchanged file is left untouched. */
    fun writeText(context: Context, dir: DocumentFile, name: String, text: String) {
        val existing = findChild(context, dir, name)
        if (existing != null) {
            val current = runCatching { context.contentResolver.openInputStream(existing.uri)?.use { it.reader().readText() } }.getOrNull()
            if (current == text) return
        }
        val file = existing ?: dir.createFile("application/octet-stream", name) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
