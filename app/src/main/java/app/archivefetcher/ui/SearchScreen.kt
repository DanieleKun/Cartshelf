package app.archivefetcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R

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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextEntry(
                value = vm.searchQuery,
                onValueChange = vm::onSearchQuery,
                hint = stringResource(R.string.search_hint_all),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focus,
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "index") { IndexCard(vm, indexedHere, totalConsoles) }
                    if (vm.searchQuery.isBlank()) {
                        item(key = "hint") {
                            Text(stringResource(R.string.search_empty_hint), color = Palette.TextDim, fontSize = 13.sp)
                        }
                    } else if (!vm.searching && result.total == 0) {
                        item(key = "none") {
                            Text(
                                stringResource(R.string.search_none),
                                color = Palette.TextDim,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(result.hits, key = { it.consoleId + "|" + it.file.key }) { hit ->
                        val selected = hit.file.key in vm.picked
                        FocusSurface({ vm.togglePick(hit) }, highlighted = selected, modifier = Modifier.fillMaxWidth(), cornerRadius = 10) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row {
                                    Text(
                                        if (selected) "✓" else "○",
                                        color = if (selected) Palette.Accent else Palette.TextDim,
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(hit.file.displayName, color = Palette.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    listOf(vm.consoleName(hit.consoleId), formatBytes(hit.file.size), hit.file.regions.joinToString(", "))
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    color = Palette.TextDim,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (result.total > result.hits.size) {
                        item(key = "more") {
                            Text(
                                stringResource(R.string.search_more, result.hits.size, result.total),
                                color = Palette.TextDim,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            SelectionBar(
                vm.notice?.takeIf { vm.picked.isEmpty() }
                    ?: if (vm.picked.isEmpty()) stringResource(R.string.search_pick_hint)
                    else stringResource(R.string.selected_summary, vm.picked.size, formatBytes(chosenBytes)),
            ) {
                AppButton(stringResource(R.string.clear_short, vm.picked.size), { vm.clearPicks() }, Modifier.weight(1f))
                AppButton(stringResource(R.string.later_n, vm.picked.size), { vm.laterFromPicks() }, Modifier.weight(1f))
                AppButton(
                    stringResource(R.string.download_n, vm.picked.size), { vm.downloadPicks() }, Modifier.weight(1f),
                    highlighted = vm.picked.isNotEmpty(),
                )
            }
        }
    }
}

/** Says how much of the catalog is searchable and lets the user build, update or drop the local index. */
@Composable
private fun IndexCard(vm: MainViewModel, indexedCount: Int, totalConsoles: Int) {
    val progress by vm.indexProgress.collectAsState()
    val complete = totalConsoles > 0 && indexedCount >= totalConsoles
    if (complete && !progress.running && progress.skippedPacks == 0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.index_complete), color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            AppButton(stringResource(R.string.index_update), { vm.clearIndex(); vm.buildIndex(onlyMissing = false) })
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress.running) {
            Text(stringResource(R.string.index_indexing, progress.doneConsoles, progress.totalConsoles), color = Palette.Accent, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { if (progress.totalConsoles > 0) progress.doneConsoles.toFloat() / progress.totalConsoles else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
            )
            AppButton(stringResource(R.string.index_cancel), { vm.cancelIndex() }, Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.index_status, indexedCount, totalConsoles), color = Palette.Warning, fontSize = 13.sp)
            if (progress.skippedPacks > 0) {
                Text(stringResource(R.string.index_skipped, progress.skippedPacks), color = Palette.TextDim, fontSize = 12.sp)
            }
            Text(stringResource(R.string.index_hint), color = Palette.TextDim, fontSize = 12.sp)
            AppButton(stringResource(R.string.index_build), { vm.buildIndex() }, Modifier.fillMaxWidth(), highlighted = true)
        }
    }
}
