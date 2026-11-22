package app.archivefetcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import app.archivefetcher.addon.ConsoleInfo
import app.archivefetcher.data.RomFile
import app.archivefetcher.download.DownloadState
import app.archivefetcher.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves the console from the current catalog; it can vanish if its addon is removed while this screen is open. */
@Composable
fun ConsoleScreen(vm: MainViewModel, screen: Screen.Console) {
    val catalog by vm.catalog.collectAsState()
    val console = catalog.console(screen.consoleId)?.info
    if (console == null) {
        ScreenFrame(title = "", onBack = { vm.back() }) { CenterMessage(stringResource(R.string.console_not_found)) }
    } else {
        ConsoleContent(vm, console)
    }
}

@Composable
private fun ConsoleContent(vm: MainViewModel, console: ConsoleInfo) {
    val context = LocalContext.current
    val tasks by vm.graph.downloads.tasks.collectAsState()
    var pendingDownload by remember { mutableStateOf<List<RomFile>?>(null) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocusWhenReady() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setFolder(console, uri.toString())
            pendingDownload?.let { vm.enqueue(console, it) }
        }
        pendingDownload = null
    }

    val hasDestination = remember(vm.folderRevision, console) { vm.hasDestination(console) }
    val folderName by produceState<String?>(null, vm.folderRevision, console) {
        value = withContext(Dispatchers.IO) { vm.graph.folders.label(console.id, console.name) }
    }
    val doneHere = tasks.count { it.consoleName == console.name && it.state == DownloadState.Done }
    LaunchedEffect(console, vm.folderRevision, doneHere) { vm.refreshPresent(console) }

    val library = vm.library
    val visible = remember(library.files, vm.filters, vm.query, vm.present) { library.files.filter { vm.isVisible(it) } }
    val chosen = remember(library.files, vm.selected) { library.files.filter { it.key in vm.selected } }
    val taskByName = remember(tasks, console) {
        tasks.filter { it.consoleName == console.name }.associateBy { it.fileName }
    }

    ScreenFrame(
        title = console.name,
        subtitle = folderName ?: stringResource(R.string.folder_not_set),
        onBack = { vm.back() },
        actions = { AppButton(stringResource(R.string.folder), { picker.launch(null) }) },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextEntry(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    hint = stringResource(R.string.search_hint),
                    modifier = Modifier.weight(1f),
                    focusRequester = searchFocus,
                )
                val activeFilters = vm.filters.activeCount
                AppButton(
                    if (activeFilters > 0) stringResource(R.string.filters_count, activeFilters) else stringResource(R.string.filters),
                    { vm.open(Screen.Filters) },
                    highlighted = activeFilters > 0,
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    library.error != null -> CenterMessage(
                        stringResource(R.string.catalog_load_failed, library.error),
                        stringResource(R.string.retry),
                    ) { vm.reloadLibrary() }
                    library.files.isEmpty() && library.loading -> LoadingWithProgress(library)
                    library.files.isEmpty() && library.failedPacks.isNotEmpty() ->
                        Column(Modifier.fillMaxSize().padding(top = 8.dp)) { FailedPacksCard(library, vm::retryFailed) }
                    library.files.isEmpty() -> CenterMessage(stringResource(R.string.no_packs))
                    else -> FileList(vm, library, visible, taskByName)
                }
            }

            ActionBar(
                visibleCount = visible.size,
                selectedCount = chosen.size,
                selectedBytes = chosen.sumOf { it.size },
                onToggleAll = {
                    if (vm.selected.isEmpty()) {
                        vm.select(visible.filter { !vm.isPresent(it) && taskByName[it.displayName]?.let { t -> t.isActive || t.state == DownloadState.Done } != true })
                    } else {
                        vm.clearSelection()
                    }
                },
                notice = vm.notice,
                onLater = { if (chosen.isNotEmpty()) vm.addLater(console, chosen) },
                onDownload = {
                    if (chosen.isNotEmpty()) {
                        if (!hasDestination) {
                            pendingDownload = chosen
                            picker.launch(null)
                        } else {
                            vm.enqueue(console, chosen)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingWithProgress(library: Library) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Loading()
        Text(
            stringResource(R.string.loading_packs, library.loaded, library.total),
            color = Palette.TextDim,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun FileList(
    vm: MainViewModel,
    library: Library,
    visible: List<RomFile>,
    taskByName: Map<String, DownloadTask>,
) {
    // One column on square and upright screens, more on wide ones so landscape phones and TVs show more files.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FILE_CELL_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (library.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.loading_packs, library.loaded, library.total),
                        color = Palette.TextDim,
                        fontSize = 12.sp,
                    )
                    LinearProgressIndicator(
                        progress = { if (library.total > 0) library.loaded.toFloat() / library.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Palette.Accent,
                        trackColor = Palette.SurfaceHigh,
                    )
                }
            }
        }
        if (library.failedPacks.isNotEmpty()) {
            item(key = "failed", span = { GridItemSpan(maxLineSpan) }) { FailedPacksCard(library, vm::retryFailed) }
        }
        if (library.hasLocked && !vm.graph.prefs.hasAuth) {
            item(key = "locked", span = { GridItemSpan(maxLineSpan) }) {
                FocusSurface(onClick = { vm.open(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(stringResource(R.string.locked_title), color = Palette.Warning, fontSize = 14.sp)
                        Text(stringResource(R.string.locked_body), color = Palette.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.no_results),
                    color = Palette.TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(visible, key = { it.key }) { file ->
            FileRow(file, selected = file.key in vm.selected, task = taskByName[file.displayName], present = vm.isPresent(file)) { vm.toggle(file) }
        }
    }
}

/** Names every collection that could not be loaded and why, with a retry for the ones that failed on the network. */
@Composable
private fun FailedPacksCard(library: Library, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.packs_failed, library.failedPacks.size), color = Palette.Warning, fontSize = 13.sp)
        library.failedPacks.forEach { pack ->
            Text("• ${pack.entry.title}: ${pack.reason}", color = Palette.TextDim, fontSize = 12.sp)
        }
        Text(stringResource(R.string.packs_failed_hint), color = Palette.TextDim, fontSize = 12.sp)
        AppButton(stringResource(R.string.retry), onRetry, Modifier.fillMaxWidth())
    }
}

@Composable
private fun FileRow(file: RomFile, selected: Boolean, task: DownloadTask?, present: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, highlighted = selected, modifier = Modifier.fillMaxWidth(), cornerRadius = 10) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (selected) "✓" else "○",
                    color = if (selected) Palette.Accent else Palette.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(file.displayName, color = Palette.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metaLine(file, present),
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (task != null) {
                    Text(rowStatus(task), color = rowStatusColor(task), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (task != null && (task.state == DownloadState.Running || task.state == DownloadState.Verifying || task.state == DownloadState.Extracting)) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
        }
    }
}

@Composable
private fun metaLine(file: RomFile, present: Boolean): String {
    val parts = buildList {
        if (present) add("✓ " + stringResource(R.string.tag_present))
        add(file.format.uppercase())
        add(formatBytes(file.size))
        if (file.regions.isNotEmpty()) add(file.regions.joinToString(", "))
        if (file.languages.isNotEmpty()) add(file.languages.joinToString(","))
        if (file.locked) add(stringResource(R.string.tag_locked))
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun rowStatus(task: DownloadTask): String = when (task.state) {
    DownloadState.Running -> "${(task.progress * 100).toInt()}%"
    DownloadState.Extracting -> stringResource(R.string.row_extracting) + " ${(task.progress * 100).toInt()}%"
    DownloadState.Queued -> stringResource(R.string.row_queued)
    DownloadState.Paused -> stringResource(R.string.row_paused)
    DownloadState.Verifying -> stringResource(R.string.row_verifying) + " ${(task.progress * 100).toInt()}%"
    DownloadState.Done -> "✓ " + stringResource(if (task.verified) R.string.row_verified else R.string.row_done)
    DownloadState.Failed -> stringResource(R.string.row_failed)
}

internal fun rowStatusColor(task: DownloadTask): Color = when (task.state) {
    DownloadState.Failed -> Palette.Danger
    DownloadState.Running, DownloadState.Verifying, DownloadState.Extracting, DownloadState.Done -> Palette.Accent
    else -> Palette.TextDim
}

@Composable
private fun ActionBar(
    visibleCount: Int,
    selectedCount: Int,
    selectedBytes: Long,
    notice: String?,
    onToggleAll: () -> Unit,
    onLater: () -> Unit,
    onDownload: () -> Unit,
) {
    SelectionBar(
        if (selectedCount == 0) notice ?: stringResource(R.string.files_count, visibleCount)
        else stringResource(R.string.selected_summary, selectedCount, formatBytes(selectedBytes)),
    ) {
        AppButton(
            if (selectedCount == 0) stringResource(R.string.select_all_short, visibleCount)
            else stringResource(R.string.clear_short, selectedCount),
            onToggleAll,
            Modifier.weight(1f),
        )
        AppButton(stringResource(R.string.later_n, selectedCount), onLater, Modifier.weight(1f))
        AppButton(
            stringResource(R.string.download_n, selectedCount),
            onDownload,
            Modifier.weight(1f),
            highlighted = selectedCount > 0,
        )
    }
}

private val FILE_CELL_MIN_WIDTH = 320.dp
