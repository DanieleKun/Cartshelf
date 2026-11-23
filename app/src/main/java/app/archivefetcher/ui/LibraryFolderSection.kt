package app.archivefetcher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.archivefetcher.R
import app.archivefetcher.data.FrontendLayout
import app.archivefetcher.data.FrontendLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library folder + frontend layout, as shown in Options. */
@Composable
fun LibraryFolderSection(vm: MainViewModel, firstFocus: FocusRequester? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryFolderPicker(vm, firstFocus)
        FolderLayoutPicker(vm)
        Text(stringResource(R.string.library_hint), style = AppType.Caption)
    }
}

/** One folder for the whole library; every console gets its own sub-folder inside it. */
@Composable
fun LibraryFolderPicker(vm: MainViewModel, firstFocus: FocusRequester? = null) {
    val context = LocalContext.current
    val prefs = vm.graph.prefs
    // Set when a folder was just chosen here, so the choice is confirmed on screen.
    var justSaved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var noPicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            failed = !granted
            justSaved = granted
            if (granted) vm.setBaseFolder(uri.toString())
        }
    }
    val folderName by produceState<String?>(null, vm.folderRevision) {
        value = withContext(Dispatchers.IO) { prefs.baseFolder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } }
    }
    val folderPath = remember(vm.folderRevision) {
        prefs.baseFolder?.let { readablePath(Uri.parse(it), context.getString(R.string.storage_internal), context.getString(R.string.storage_card)) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface({ noPicker = !launchFolderPicker(context, picker) }, Modifier.fillMaxWidth(), focusRequester = firstFocus, highlighted = folderName != null) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AppIcons.Folder, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folderName ?: stringResource(R.string.library_none),
                        style = AppType.Label,
                        maxLines = 1, modifier = Modifier.scrollIfLong(),
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        folderPath ?: stringResource(if (folderName == null) R.string.library_choose_hint else R.string.library_change_hint),
                        style = AppType.Caption,
                        maxLines = 1, modifier = Modifier.scrollIfLong(),
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        val savedName = folderPath ?: folderName
        if (justSaved && savedName != null) DoneNote(stringResource(R.string.library_saved, savedName))
        if (failed) Banner(stringResource(R.string.library_failed), icon = AppIcons.Warning, tint = Palette.Danger)
        if (noPicker) Banner(stringResource(R.string.library_no_picker), icon = AppIcons.Warning, tint = Palette.Warning)
    }
}

/**
 * "Internal storage/ROMs" or "SD card/ROMs" from a folder address, for people rather than for the system. Only for
 * folders of the device's own storage; for others (Downloads, cloud apps) null, and the folder name is shown.
 */
private fun readablePath(tree: Uri, internal: String, card: String): String? {
    if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val root = when {
        volume == "primary" -> internal
        cardVolume.matches(volume) -> card
        else -> return null
    }
    return if (path.isEmpty()) root else "$root/$path"
}

/**
 * Opens the system folder picker. False when the device has none: some TV and handheld systems ship only an empty
 * placeholder that closes at once, which would otherwise look like nothing happened. Finding no handler is not
 * proof that there is none (package visibility can hide it), so only a known placeholder stops the launch.
 */
internal fun launchFolderPicker(context: Context, picker: ActivityResultLauncher<Uri?>): Boolean {
    val handler = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager)
    if (handler?.packageName in PLACEHOLDER_PICKERS) return false
    return try {
        picker.launch(null)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private val PLACEHOLDER_PICKERS = setOf("com.android.tv.frameworkpackagestubs")

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** Removable storage shows up as its volume id, e.g. `1A2B-3C4D`. */
private val cardVolume = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

/** How the console sub-folders are named, so the files land where the frontend expects them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderLayoutPicker(vm: MainViewModel) {
    val layout = remember(vm.folderRevision) { FrontendLayout.fromId(vm.graph.prefs.layoutId) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.layout_title), style = AppType.Caption)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FrontendLayout.EsDe to R.string.layout_esde,
                FrontendLayout.Batocera to R.string.layout_batocera,
                FrontendLayout.Names to R.string.layout_names,
            ).forEach { (option, label) ->
                Chip(stringResource(label), layout == option) { vm.setLayout(option) }
            }
        }
        val example = FrontendLayouts.folderName(layout, "nintendo-gbc", "Nintendo - Game Boy Color")
        Text(stringResource(R.string.layout_example, example), style = AppType.Caption.copy(color = Palette.TextFaint))
    }
}
