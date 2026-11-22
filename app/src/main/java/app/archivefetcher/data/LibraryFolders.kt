package app.archivefetcher.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Where the files of a console go: either a folder the user picked for that console, or a sub-folder of the library
 * folder named after the chosen frontend layout. All functions touch the storage framework and must run off the main thread.
 */
class LibraryFolders(private val context: Context, private val prefs: Prefs) {
    fun hasDestination(consoleId: String): Boolean = prefs.folderFor(consoleId) != null || prefs.baseFolder != null

    fun subFolderName(consoleId: String, consoleName: String): String =
        FrontendLayouts.folderName(FrontendLayout.fromId(prefs.layoutId), consoleId, consoleName)

    /** Short text for the screen: the picked folder's name, or "library/subfolder". */
    fun label(consoleId: String, consoleName: String): String? {
        prefs.folderFor(consoleId)?.let { return DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }
        val base = prefs.baseFolder ?: return null
        val baseName = DocumentFile.fromTreeUri(context, Uri.parse(base))?.name ?: return null
        return "$baseName/${subFolderName(consoleId, consoleName)}"
    }

    /** Address of the destination folder; with [create] the sub-folder is made when it does not exist yet. */
    fun resolve(consoleId: String, consoleName: String, create: Boolean): String? {
        prefs.folderFor(consoleId)?.let { return it }
        val base = DocumentFile.fromTreeUri(context, Uri.parse(prefs.baseFolder ?: return null)) ?: return null
        val name = subFolderName(consoleId, consoleName)
        val existing = base.listFiles().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        val folder = existing ?: if (create) base.createDirectory(name) else null
        return folder?.uri?.toString()
    }

    /** Lower-case names, without extension, of everything directly inside the folder (sub-folders by their full name). */
    fun presentStems(folderUri: String): Set<String> {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptySet()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, DocumentsContract.getDocumentId(dir.uri))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val stems = HashSet<String>()
        runCatching {
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (name.endsWith(".part")) continue
                    val isDir = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                    stems += (if (isDir) name else RomNaming.stemOf(name)).lowercase()
                }
            }
        }
        return stems
    }
}
