package app.archivefetcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.archivefetcher.download.LaterMode

/** Files saved for later, from every console, with the schedule that decides when they are downloaded. */
@Composable
fun LaterScreen(vm: MainViewModel) {
    val items by vm.later.collectAsState()
    val tasks by vm.graph.downloads.tasks.collectAsState()
    val taskByKey = remember(tasks) { tasks.filter { it.laterKey != null }.associateBy { it.laterKey } }
    val picked = vm.laterPicked
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
    val totalBytes = items.sumOf { it.size }

    ScreenFrame(
        title = stringResource(R.string.later_title),
        subtitle = stringResource(R.string.later_summary, items.size, formatBytes(totalBytes)),
        onBack = { vm.back() },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LaterSchedule(vm, first)
            Box(Modifier.weight(1f)) {
                if (items.isEmpty()) {
                    CenterMessage(stringResource(R.string.later_empty))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items, key = { it.consoleId + "|" + it.key }) { item ->
                            val selected = item.key in picked
                            FocusSurface({ vm.toggleLaterPick(item.key) }, highlighted = selected, modifier = Modifier.fillMaxWidth(), cornerRadius = 10) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row {
                                        Text(
                                            if (selected) "✓" else "○",
                                            color = if (selected) Palette.Accent else Palette.TextDim,
                                            fontSize = 15.sp,
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                        Text(
                                            item.toRomFile().displayName, color = Palette.Text, fontSize = 14.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row {
                                        Text(
                                            item.consoleName + " · " + formatBytes(item.size),
                                            color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        taskByKey[item.key]?.let { task ->
                                            Text(rowStatus(task), color = rowStatusColor(task), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SelectionBar(vm.notice ?: stringResource(R.string.later_summary, items.size, formatBytes(totalBytes))) {
                AppButton(
                    if (picked.isEmpty()) stringResource(R.string.select_all_short, items.size)
                    else stringResource(R.string.clear_short, picked.size),
                    { if (picked.isEmpty()) vm.selectAllLater() else vm.clearLaterPicks() },
                    Modifier.weight(1f),
                )
                AppButton(stringResource(R.string.later_remove, picked.size), { vm.removeLater() }, Modifier.weight(1f))
                AppButton(
                    stringResource(R.string.download_n, if (picked.isEmpty()) items.size else picked.size),
                    { vm.downloadLater() },
                    Modifier.weight(1f),
                    highlighted = items.isNotEmpty(),
                )
            }
        }
    }
}

/** Chooses when the list is downloaded without the user pressing a button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaterSchedule(vm: MainViewModel, first: FocusRequester) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.later_when), color = Palette.Accent, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                LaterMode.Manual to R.string.later_mode_manual,
                LaterMode.Charging to R.string.later_mode_charging,
                LaterMode.Night to R.string.later_mode_night,
            ).forEach { (mode, label) ->
                Chip(stringResource(label), vm.laterMode == mode, first.takeIf { vm.laterMode == mode }) { vm.chooseLaterMode(mode) }
            }
        }
        if (vm.laterMode == LaterMode.Night) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("−", { vm.changeLaterHour(-1) })
                Text("%02d:00".format(vm.laterHour), color = Palette.Text, fontSize = 18.sp)
                AppButton("+", { vm.changeLaterHour(1) })
            }
        }
        Text(
            stringResource(
                when (vm.laterMode) {
                    LaterMode.Manual -> R.string.later_hint
                    LaterMode.Charging -> R.string.later_hint_charging
                    LaterMode.Night -> R.string.later_hint_night
                },
            ),
            color = Palette.TextDim,
            fontSize = 12.sp,
        )
    }
}
