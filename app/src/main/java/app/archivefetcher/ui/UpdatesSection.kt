package app.archivefetcher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.archivefetcher.BuildConfig
import app.archivefetcher.R
import app.archivefetcher.data.UpdateCheck
import kotlinx.coroutines.launch

/** The new-version notice on or off, and a button that asks GitHub right away, with the answer on screen. */
@Composable
internal fun AppUpdatesSection(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.graph.prefs
    val scope = rememberCoroutineScope()
    var notify by remember { mutableStateOf(prefs.checkUpdates) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheck?>(null) }

    OnOffRow(stringResource(R.string.check_updates), notify) { notify = it; prefs.checkUpdates = it }
    Hint(stringResource(R.string.check_updates_hint))
    AppButton(
        stringResource(if (checking) R.string.update_checking else R.string.update_check_now),
        {
            if (!checking) {
                checking = true
                result = null
                scope.launch {
                    try {
                        val answer = vm.graph.updates.checkNow()
                        if (answer is UpdateCheck.Available) vm.showUpdate(answer.info)
                        result = answer
                    } finally {
                        checking = false
                    }
                }
            }
        },
        Modifier.fillMaxWidth(),
        enabled = !checking,
        icon = AppIcons.Refresh,
    )
    when (val answer = result) {
        is UpdateCheck.Available -> Banner(
            stringResource(R.string.update_available, answer.info.version),
            icon = AppIcons.Download,
            tint = Palette.Accent,
            action = stringResource(R.string.update_open),
            onAction = { openLink(context, answer.info.url) },
        )
        UpdateCheck.UpToDate -> DoneNote(stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
        UpdateCheck.Failed -> Banner(stringResource(R.string.update_check_failed), icon = AppIcons.Warning, tint = Palette.Warning)
        null -> Unit
    }
}
