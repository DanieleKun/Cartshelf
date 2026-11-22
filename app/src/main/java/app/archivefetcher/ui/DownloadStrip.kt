package app.archivefetcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.archivefetcher.R
import app.archivefetcher.download.DownloadState

/** Always-visible summary of the queue; opens the full list. Slides away when there are no downloads. */
@Composable
fun DownloadStrip(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.graph.downloads.tasks.collectAsState()
    AnimatedVisibility(tasks.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val active = tasks.filter { it.isActive }
        val done = tasks.count { it.state == DownloadState.Done }
        val failed = tasks.count { it.state == DownloadState.Failed }
        FocusSurface(onClick = { vm.open(Screen.Downloads) }, modifier = modifier.fillMaxWidth(), cornerRadius = 16) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (failed > 0 && active.isEmpty()) AppIcons.Warning else AppIcons.Download,
                    contentDescription = null,
                    tint = if (failed > 0 && active.isEmpty()) Palette.Danger else if (active.isNotEmpty()) Palette.Accent else Palette.Success,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (active.isNotEmpty()) {
                        val sized = active.filter { it.totalBytes > 0 }
                        val total = sized.sumOf { it.totalBytes }.coerceAtLeast(1)
                        val received = sized.sumOf { it.doneBytes.coerceAtMost(it.totalBytes) }
                        val percent = (received * 100 / total).toInt()
                        Text(
                            stringResource(R.string.strip_active, active.size, percent, formatBytes(active.sumOf { it.bytesPerSec })),
                            style = AppType.Label,
                            maxLines = 1,
                        )
                        ProgressBar(received.toFloat() / total, height = 3.dp)
                    } else {
                        Text(stringResource(R.string.strip_idle, done, tasks.size - done), style = AppType.Label, maxLines = 1)
                    }
                }
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}
