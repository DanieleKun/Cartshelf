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
import app.archivefetcher.R
import app.archivefetcher.download.LaterMode

/** Files saved for later, from every console, with the schedule that decides when they are downloaded. */
@OptIn(ExperimentalLayoutApi::class)
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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LaterSchedule(vm, first)
            Box(Modifier.weight(1f)) {
                if (items.isEmpty()) {
                    EmptyState(AppIcons.Clock, stringResource(R.string.later_empty_title), stringResource(R.string.later_empty), tint = Palette.TextDim)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.consoleId + "|" + it.key }) { item ->
                            val selected = item.key in picked
                            FocusSurface({ vm.toggleLaterPick(item.key) }, highlighted = selected, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SelectMark(selected)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(item.toRomFile().displayName, style = AppType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Pill(item.consoleName, Palette.Accent, Palette.AccentSoft)
                                            Pill(formatBytes(item.size))
                                            taskByKey[item.key]?.let { task ->
                                                Pill(rowStatus(task), rowStatusColor(task), rowStatusColor(task).copy(alpha = 0.12f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (items.isNotEmpty()) SelectionBar(vm.notice ?: stringResource(R.string.later_summary, items.size, formatBytes(totalBytes))) {
                AppButton(
                    if (picked.isEmpty()) stringResource(R.string.select_all_short, items.size)
                    else stringResource(R.string.clear_short, picked.size),
                    { if (picked.isEmpty()) vm.selectAllLater() else vm.clearLaterPicks() },
                    Modifier.weight(1f),
                    enabled = items.isNotEmpty(),
                )
                AppButton(stringResource(R.string.remove), { vm.removeLater() }, Modifier.weight(1f), icon = AppIcons.Delete, enabled = picked.isNotEmpty())
                AppButton(
                    stringResource(R.string.download_short),
                    { vm.downloadLater() },
                    Modifier.weight(1f),
                    highlighted = items.isNotEmpty(),
                    icon = AppIcons.Download,
                    enabled = items.isNotEmpty(),
                )
            }
        }
    }
}

/** Chooses when the list is downloaded without the user pressing a button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaterSchedule(vm: MainViewModel, first: FocusRequester) {
    Panel {
        Overline(stringResource(R.string.later_when))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LaterMode.Manual to R.string.later_mode_manual,
                LaterMode.Charging to R.string.later_mode_charging,
                LaterMode.Night to R.string.later_mode_night,
            ).forEach { (mode, label) ->
                Chip(stringResource(label), vm.laterMode == mode, first.takeIf { vm.laterMode == mode }) { vm.chooseLaterMode(mode) }
            }
        }
        if (vm.laterMode == LaterMode.Night) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("−", { vm.changeLaterHour(-1) })
                Text("%02d:00".format(vm.laterHour), style = AppType.Title)
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
            style = AppType.Caption,
        )
    }
}
