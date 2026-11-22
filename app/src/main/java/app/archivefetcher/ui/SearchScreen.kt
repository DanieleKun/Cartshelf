package app.archivefetcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.archivefetcher.R
import app.archivefetcher.data.IndexHit
import app.archivefetcher.download.IndexJobState

/** Search across every console that has an index; results can be downloaded now or saved for later. */
@Composable
fun SearchScreen(vm: MainViewModel) {
    val catalog by vm.catalog.collectAsState()
    val indexed by vm.indexed.collectAsState()
    val consoleIds = remember(catalog) { catalog.categories.flatMap { category -> category.consoles.map { it.info.id } } }
    val totalConsoles = consoleIds.size
    val indexedHere = remember(consoleIds, indexed) { consoleIds.count { it in indexed } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
    val result = vm.searchResult
    val chosenBytes = vm.picked.values.sumOf { it.file.size }

    ScreenFrame(
        title = stringResource(R.string.search_title),
        subtitle = when {
            vm.searchQuery.isBlank() -> stringResource(R.string.index_status, indexedHere, totalConsoles)
            vm.searching -> stringResource(R.string.searching)
            else -> stringResource(R.string.search_results, result.total)
        },
        onBack = { vm.back() },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextEntry(
                value = vm.searchQuery,
                onValueChange = vm::onSearchQuery,
                hint = stringResource(R.string.search_hint_all),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focus,
                icon = AppIcons.Search,
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "index") { IndexCard(vm, indexedHere, totalConsoles) }
                    when {
                        vm.searching && result.hits.isEmpty() -> item(key = "searching") {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.search_progress, indexedHere), style = AppType.Caption)
                            }
                        }
                        vm.searchQuery.isBlank() -> item(key = "hint") {
                            Box(Modifier.padding(top = 24.dp)) {
                                EmptyState(
                                    AppIcons.Search,
                                    stringResource(R.string.search_empty_title),
                                    stringResource(R.string.search_empty_hint),
                                    tint = Palette.TextDim,
                                    fillScreen = false,
                                )
                            }
                        }
                        !vm.searching && result.total == 0 -> item(key = "none") {
                            Box(Modifier.padding(top = 24.dp)) {
                                EmptyState(
                                    AppIcons.Search,
                                    stringResource(R.string.search_none),
                                    stringResource(if (indexedHere < totalConsoles) R.string.search_none_partial else R.string.search_none_body),
                                    tint = Palette.TextDim,
                                    fillScreen = false,
                                )
                            }
                        }
                        result.approximate -> item(key = "approximate") {
                            Banner(stringResource(R.string.search_approximate, vm.searchQuery))
                        }
                    }
                    items(result.hits, key = { it.consoleId + "|" + it.file.key }) { hit ->
                        HitRow(hit, vm.consoleName(hit.consoleId), selected = hit.file.key in vm.picked) { vm.togglePick(hit) }
                    }
                    if (result.total > result.hits.size) {
                        item(key = "more") {
                            Text(
                                stringResource(R.string.search_more, result.hits.size, result.total),
                                style = AppType.Caption,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            // The action bar appears only when there is something to act on, leaving room for results on small screens.
            if (vm.picked.isNotEmpty() || vm.notice != null) SelectionBar(
                vm.notice?.takeIf { vm.picked.isEmpty() }
                    ?: if (vm.picked.isEmpty()) stringResource(R.string.search_pick_hint)
                    else stringResource(R.string.selected_summary, vm.picked.size, formatBytes(chosenBytes)),
            ) {
                AppButton(stringResource(R.string.clear_short, vm.picked.size), { vm.clearPicks() }, Modifier.weight(1f), enabled = vm.picked.isNotEmpty())
                AppButton(stringResource(R.string.later_short), { vm.laterFromPicks() }, Modifier.weight(1f), icon = AppIcons.Clock, enabled = vm.picked.isNotEmpty())
                AppButton(
                    stringResource(R.string.download_short), { vm.downloadPicks() }, Modifier.weight(1f),
                    highlighted = vm.picked.isNotEmpty(), icon = AppIcons.Download, enabled = vm.picked.isNotEmpty(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitRow(hit: IndexHit, consoleName: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, highlighted = selected, modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectMark(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(hit.file.displayName, style = AppType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(consoleName, Palette.Accent, Palette.AccentSoft)
                    Pill(formatBytes(hit.file.size))
                    if (hit.file.languages.isNotEmpty()) Pill(hit.file.languages.joinToString(" "))
                }
            }
        }
    }
}

/**
 * Says how much of the catalog is searchable and what the background job is doing, in plain words:
 * not started, waiting for a connection, reading console N of M, or done.
 */
@Composable
private fun IndexCard(vm: MainViewModel, indexedCount: Int, totalConsoles: Int) {
    val progress by vm.indexProgress.collectAsState()
    val job by vm.indexJob.collectAsState()
    val complete = totalConsoles > 0 && indexedCount >= totalConsoles
    val state = when {
        job == IndexJobState.Running || progress.running -> IndexCardState.Running
        job == IndexJobState.Waiting -> IndexCardState.Waiting
        complete -> IndexCardState.Complete
        else -> IndexCardState.Missing
    }
    // The button pressed disappears when the card changes; hand focus to the new card's button so the D-pad stays put.
    val actionFocus = remember { FocusRequester() }
    var userActed by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (userActed) {
            actionFocus.requestFocusWhenReady()
            userActed = false
        }
    }
    val start = { userActed = true; vm.buildIndex() }
    val stop = { userActed = true; vm.cancelIndex() }
    AnimatedContent(state, transitionSpec = { fadeIn(tween(DURATION_MEDIUM)) togetherWith fadeOut(tween(DURATION_SHORT)) }, label = "indexCard") { shown ->
        when (shown) {
            IndexCardState.Complete -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Check, contentDescription = null, tint = Palette.Success, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.index_complete), style = AppType.Caption, modifier = Modifier.weight(1f))
                AppButton(stringResource(R.string.index_update), { vm.rebuildIndex() }, icon = AppIcons.Refresh)
            }
            IndexCardState.Running -> Panel {
                Text(stringResource(R.string.index_running_title), style = AppType.Label)
                Text(
                    if (progress.totalConsoles == 0) stringResource(R.string.index_notif_preparing)
                    else stringResource(
                        R.string.index_notif_progress,
                        (progress.doneConsoles + 1).coerceAtMost(progress.totalConsoles), progress.totalConsoles,
                        progress.currentConsole.orEmpty(),
                    ),
                    style = AppType.Caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress.totalConsoles > 0) ProgressBar(progress.fraction) else IndeterminateBar()
                Text(stringResource(R.string.index_background_note), style = AppType.Caption.copy(color = Palette.TextFaint))
                AppButton(stringResource(R.string.index_stop), stop, Modifier.fillMaxWidth(), icon = AppIcons.Close, focusRequester = actionFocus)
            }
            IndexCardState.Waiting -> Panel {
                Text(stringResource(R.string.index_waiting_title), style = AppType.Label)
                Text(stringResource(R.string.index_waiting_body), style = AppType.Caption)
                AppButton(stringResource(R.string.index_stop), stop, Modifier.fillMaxWidth(), icon = AppIcons.Close, focusRequester = actionFocus)
            }
            IndexCardState.Missing -> Panel(border = Palette.Accent.copy(alpha = 0.35f)) {
                Text(
                    if (indexedCount == 0) stringResource(R.string.index_missing_title)
                    else stringResource(R.string.index_partial_title, indexedCount, totalConsoles),
                    style = AppType.Label,
                )
                Text(stringResource(R.string.index_hint), style = AppType.Caption)
                if (progress.skippedPacks > 0) {
                    Text(stringResource(R.string.index_skipped, progress.skippedPacks), style = AppType.Caption.copy(color = Palette.Warning))
                }
                AppButton(
                    stringResource(if (indexedCount == 0) R.string.index_build else R.string.index_continue),
                    start,
                    Modifier.fillMaxWidth(),
                    highlighted = true,
                    icon = AppIcons.Download,
                    focusRequester = actionFocus,
                )
            }
        }
    }
}

private enum class IndexCardState { Missing, Waiting, Running, Complete }
