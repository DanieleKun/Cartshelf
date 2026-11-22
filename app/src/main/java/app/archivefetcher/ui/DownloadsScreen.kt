package app.archivefetcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import app.archivefetcher.download.DownloadState
import app.archivefetcher.download.DownloadTask

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val tasks by vm.graph.downloads.tasks.collectAsState()
    val manager = vm.graph.downloads
    ScreenFrame(
        title = stringResource(R.string.downloads),
        subtitle = stringResource(R.string.downloads_summary, tasks.count { it.isActive }, tasks.size),
        onBack = { vm.back() },
        actions = { AppButton(stringResource(R.string.clear_done), { manager.clearFinished() }) },
    ) {
        if (tasks.isEmpty()) {
            CenterMessage(stringResource(R.string.downloads_empty))
        } else {
            val firstAction = remember { FocusRequester() }
            LaunchedEffect(tasks.firstOrNull()?.id) { firstAction.requestFocusWhenReady() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task,
                        firstFocus = firstAction.takeIf { task.id == tasks.first().id },
                        onPause = { manager.pause(task.id) },
                        onResume = { manager.resume(task.id) },
                        onCancel = { manager.cancel(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    firstFocus: FocusRequester?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(task.consoleName, color = Palette.Accent, fontSize = 11.sp, maxLines = 1)
        Text(task.fileName, color = Palette.Text, fontSize = 14.sp, maxLines = 2)
        LinearProgressIndicator(
            progress = { task.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (task.state == DownloadState.Failed) Palette.Danger else Palette.Accent,
            trackColor = Palette.SurfaceHigh,
        )
        Text(statusLine(task), color = statusColor(task), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = task.isActive
            val finished = task.state == DownloadState.Done
            // One button whose label flips keeps D-pad focus on it when a download is paused or resumed.
            if (!finished) {
                AppButton(
                    stringResource(if (running) R.string.pause else R.string.resume),
                    if (running) onPause else onResume,
                    Modifier.weight(1f),
                    highlighted = !running,
                    focusRequester = firstFocus,
                )
            }
            AppButton(
                stringResource(if (finished) R.string.remove else R.string.cancel),
                onCancel,
                Modifier.weight(1f),
                focusRequester = if (finished) firstFocus else null,
            )
        }
    }
}

@Composable
private fun statusLine(task: DownloadTask): String = when (task.state) {
    DownloadState.Queued -> stringResource(R.string.status_queued, formatBytes(task.totalBytes))
    DownloadState.Running -> task.message ?: stringResource(
        R.string.status_running, formatBytes(task.doneBytes), formatBytes(task.totalBytes), formatBytes(task.bytesPerSec),
    )
    DownloadState.Verifying -> stringResource(R.string.status_verifying, (task.progress * 100).toInt())
    DownloadState.Extracting -> stringResource(R.string.status_extracting, (task.progress * 100).toInt())
    DownloadState.Paused -> stringResource(R.string.status_paused, formatBytes(task.doneBytes), formatBytes(task.totalBytes))
    DownloadState.Done -> stringResource(if (task.verified) R.string.status_done_verified else R.string.status_done, formatBytes(task.totalBytes))
    DownloadState.Failed -> task.message ?: stringResource(R.string.status_failed)
}

private fun statusColor(task: DownloadTask) = when (task.state) {
    DownloadState.Failed -> Palette.Danger
    DownloadState.Done -> Palette.Accent
    else -> Palette.TextDim
}
