package app.archivefetcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import app.archivefetcher.R
import app.archivefetcher.data.FrontendLayout
import app.archivefetcher.data.FrontendLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library folder + frontend layout: one folder is picked and every console gets its own sub-folder inside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryFolderSection(vm: MainViewModel, firstFocus: FocusRequester? = null) {
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
    val layout = remember(vm.folderRevision) { FrontendLayout.fromId(prefs.layoutId) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            folderName?.let { stringResource(R.string.library_current, it) } ?: stringResource(R.string.library_none),
            color = if (folderName != null) Palette.Accent else Palette.TextDim,
            fontSize = 13.sp,
        )
        AppButton(
            stringResource(R.string.library_choose), { picker.launch(null) }, Modifier.fillMaxWidth(),
            highlighted = folderName == null, focusRequester = firstFocus,
        )
        Text(stringResource(R.string.layout_title), color = Palette.TextDim, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                FrontendLayout.EsDe to R.string.layout_esde,
                FrontendLayout.Batocera to R.string.layout_batocera,
                FrontendLayout.Names to R.string.layout_names,
            ).forEach { (option, label) ->
                Chip(stringResource(label), layout == option) { vm.setLayout(option) }
            }
        }
        val example = FrontendLayouts.folderName(layout, "nintendo-gbc", "Nintendo - Game Boy Color")
        Text(stringResource(R.string.layout_example, example), color = Palette.TextDim, fontSize = 12.sp)
        Text(stringResource(R.string.library_hint), color = Palette.TextDim, fontSize = 12.sp)
    }
}
