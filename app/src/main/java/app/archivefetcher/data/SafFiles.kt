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
}
