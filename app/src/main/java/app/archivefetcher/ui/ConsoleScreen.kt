package app.archivefetcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import app.archivefetcher.R
import app.archivefetcher.addon.ConsoleInfo
import app.archivefetcher.data.FileFilters
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
        ScreenFrame(title = "", onBack = { vm.back() }) {
            EmptyState(AppIcons.Warning, stringResource(R.string.console_not_found), tint = Palette.Warning)
        }
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
    val visible = remember(library.files, vm.filters, vm.query, vm.present) { vm.visibleFiles(library.files) }
    val chosen = remember(library.files, vm.selected) { library.files.filter { it.key in vm.selected } }
    val taskByName = remember(tasks, console) {
        tasks.filter { it.consoleName == console.name }.associateBy { it.fileName }
    }

    ScreenFrame(
        title = console.name,
        subtitle = folderName?.let { stringResource(R.string.folder_label, it) } ?: stringResource(R.string.folder_not_set),
        onBack = { vm.back() },
        actions = { IconAction(AppIcons.Folder, stringResource(R.string.folder_choose), { picker.launch(null) }) },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextEntry(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    hint = stringResource(R.string.search_hint),
                    modifier = Modifier.weight(1f),
                    focusRequester = searchFocus,
                    icon = AppIcons.Search,
                )
                val activeFilters = vm.filters.activeCount
                IconAction(
                    AppIcons.Filter,
                    stringResource(R.string.filters),
                    { vm.open(Screen.Filters) },
                    badge = activeFilters.takeIf { it > 0 }?.toString(),
                    highlighted = activeFilters > 0,
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    library.error != null -> EmptyState(
                        AppIcons.CloudOff,
                        stringResource(R.string.console_error_title),
                        library.error,
                        tint = Palette.Danger,
                        action = stringResource(R.string.retry),
                        onAction = { vm.reloadLibrary() },
                    )
                    library.files.isEmpty() && library.loading -> LoadingState(
                        title = stringResource(R.string.console_loading_title),
                        detail = loadingDetail(library),
                        // Until the first collection arrives there is nothing to measure: show movement, not an empty bar.
                        progress = if (library.loaded > 0 && library.total > 0) library.loaded.toFloat() / library.total else null,
                        hint = stringResource(R.string.console_loading_hint),
                    )
                    library.files.isEmpty() && library.failedPacks.isNotEmpty() -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FailedPacksCard(library, vm::retryFailed)
                    }
                    library.files.isEmpty() -> EmptyState(
                        AppIcons.Gamepad,
                        stringResource(R.string.console_empty_title),
                        stringResource(R.string.no_packs),
                        tint = Palette.TextDim,
                    )
                    else -> FileList(vm, library, visible, taskByName)
                }
            }

            ActionBar(
                visibleCount = visible.files.size,
                selectedCount = chosen.size,
                selectedBytes = chosen.sumOf { it.size },
                onToggleAll = {
                    if (vm.selected.isEmpty()) {
                        vm.select(
                            visible.files.filter {
                                !vm.isPresent(it) && taskByName[it.displayName]?.let { t -> t.isActive || t.state == DownloadState.Done } != true
                            },
                        )
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
private fun loadingDetail(library: Library): String {
    val count = stringResource(R.string.console_loading_detail, library.loaded, library.total)
    return library.lastPack?.let { "$count · ${stringResource(R.string.console_loading_last, it)}" } ?: count
}

@Composable
private fun FileList(
    vm: MainViewModel,
    library: Library,
    visible: VisibleFiles,
    taskByName: Map<String, DownloadTask>,
) {
    // One column on square and upright screens, more on wide ones so landscape phones and TVs show more files.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FILE_CELL_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (library.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Panel(spacing = 6.dp) {
                    Text(loadingDetail(library), style = AppType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressBar(if (library.total > 0) library.loaded.toFloat() / library.total else 0f)
                }
            }
        }
        if (library.failedPacks.isNotEmpty()) {
            item(key = "failed", span = { GridItemSpan(maxLineSpan) }) { FailedPacksCard(library, vm::retryFailed) }
        }
        if (library.hasLocked && !vm.graph.prefs.hasAuth) {
            item(key = "locked", span = { GridItemSpan(maxLineSpan) }) {
                Banner(
                    stringResource(R.string.locked_body),
                    icon = AppIcons.Lock,
                    tint = Palette.Warning,
                    action = stringResource(R.string.locked_action),
                    onAction = { vm.open(Screen.Settings) },
                )
            }
        }
        if (visible.approximate) {
            item(key = "approximate", span = { GridItemSpan(maxLineSpan) }) {
                Banner(stringResource(R.string.search_approximate, vm.query))
            }
        }
        if (visible.files.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    EmptyState(
                        AppIcons.Search,
                        stringResource(R.string.no_results_title),
                        stringResource(R.string.no_results),
                        tint = Palette.TextDim,
                        action = stringResource(R.string.filters_clear).takeIf { vm.filters.activeCount > 0 || vm.query.isNotBlank() },
                        onAction = {
                            vm.filters = FileFilters()
                            vm.query = ""
                        },
                        fillScreen = false,
                    )
                }
            }
        }
        items(visible.files, key = { it.key }) { file ->
            FileRow(file, selected = file.key in vm.selected, task = taskByName[file.displayName], present = vm.isPresent(file)) { vm.toggle(file) }
        }
    }
}

/** Names every collection that could not be loaded and why, with a retry for the ones that failed on the network. */
@Composable
private fun FailedPacksCard(library: Library, onRetry: () -> Unit) {
    Panel(border = Palette.Warning.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Warning, modifier = Modifier.padding(end = 2.dp))
            Text(stringResource(R.string.packs_failed, library.failedPacks.size), style = AppType.Label)
        }
        library.failedPacks.forEach { pack ->
            Text("${pack.entry.title}: ${pack.reason}", style = AppType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.packs_failed_hint), style = AppType.Caption.copy(color = Palette.TextFaint))
        AppButton(stringResource(R.string.retry), onRetry, Modifier.fillMaxWidth(), icon = AppIcons.Refresh)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileRow(file: RomFile, selected: Boolean, task: DownloadTask?, present: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, highlighted = selected, modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectMark(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(file.displayName, style = AppType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(file.format.uppercase())
                    Pill(formatBytes(file.size))
                    if (file.languages.isNotEmpty()) Pill(file.languages.joinToString(" "))
                    if (present) Pill(stringResource(R.string.tag_present), Palette.Success, Palette.Success.copy(alpha = 0.12f))
                    if (file.locked) Pill(stringResource(R.string.tag_locked), Palette.Warning, Palette.Warning.copy(alpha = 0.12f))
                    if (task != null) Pill(rowStatus(task), rowStatusColor(task), rowStatusColor(task).copy(alpha = 0.12f))
                }
                if (task != null && (task.state == DownloadState.Running || task.state == DownloadState.Verifying || task.state == DownloadState.Extracting)) {
                    ProgressBar(task.progress, height = 3.dp)
                }
            }
        }
    }
}

@Composable
internal fun rowStatus(task: DownloadTask): String = when (task.state) {
    DownloadState.Running -> "${(task.progress * 100).toInt()}%"
    DownloadState.Extracting -> stringResource(R.string.row_extracting) + " ${(task.progress * 100).toInt()}%"
    DownloadState.Queued -> stringResource(R.string.row_queued)
    DownloadState.Paused -> stringResource(R.string.row_paused)
    DownloadState.Verifying -> stringResource(R.string.row_verifying) + " ${(task.progress * 100).toInt()}%"
    DownloadState.Done -> stringResource(if (task.verified) R.string.row_verified else R.string.row_done)
    DownloadState.Failed -> stringResource(R.string.row_failed)
}

internal fun rowStatusColor(task: DownloadTask): Color = when (task.state) {
    DownloadState.Failed -> Palette.Danger
    DownloadState.Done -> Palette.Success
    DownloadState.Running, DownloadState.Verifying, DownloadState.Extracting -> Palette.Accent
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
            enabled = selectedCount > 0 || visibleCount > 0,
        )
        AppButton(stringResource(R.string.later_short), onLater, Modifier.weight(1f), icon = AppIcons.Clock, enabled = selectedCount > 0)
        AppButton(
            stringResource(R.string.download_short),
            onDownload,
            Modifier.weight(1f),
            highlighted = selectedCount > 0,
            icon = AppIcons.Download,
            enabled = selectedCount > 0,
        )
    }
}

private val FILE_CELL_MIN_WIDTH = 320.dp
