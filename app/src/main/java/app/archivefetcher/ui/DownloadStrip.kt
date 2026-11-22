package app.archivefetcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import app.archivefetcher.download.DownloadState

/** Always-visible summary of the queue; opens the full list. Hidden when there are no downloads. */
@Composable
fun DownloadStrip(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.graph.downloads.tasks.collectAsState()
    if (tasks.isEmpty()) return

    val active = tasks.filter { it.isActive }
    val done = tasks.count { it.state == DownloadState.Done }
    FocusSurface(onClick = { vm.open(Screen.Downloads) }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (active.isNotEmpty()) {
                val sized = active.filter { it.totalBytes > 0 }
                val total = sized.sumOf { it.totalBytes }.coerceAtLeast(1)
                val received = sized.sumOf { it.doneBytes.coerceAtMost(it.totalBytes) }
                val percent = (received * 100 / total).toInt()
                Text(
                    "↓ " + stringResource(R.string.strip_active, active.size, percent, formatBytes(active.sumOf { it.bytesPerSec })),
                    color = Palette.Accent,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                LinearProgressIndicator(
                    progress = { received.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            } else {
                Text(
                    "↓ " + stringResource(R.string.strip_idle, done, tasks.size - done),
                    color = Palette.TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
