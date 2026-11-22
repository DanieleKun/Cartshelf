package app.archivefetcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setBaseFolder(uri.toString())
        }
    }
    val folderName by produceState<String?>(null, vm.folderRevision) {
        value = withContext(Dispatchers.IO) { prefs.baseFolder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } }
    }
    FocusSurface({ picker.launch(null) }, Modifier.fillMaxWidth(), focusRequester = firstFocus, highlighted = folderName != null) {
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
                    stringResource(if (folderName == null) R.string.library_choose_hint else R.string.library_change_hint),
                    style = AppType.Caption,
                )
            }
        }
    }
}

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
