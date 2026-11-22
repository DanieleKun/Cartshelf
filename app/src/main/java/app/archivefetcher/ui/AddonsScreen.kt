package app.archivefetcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.archivefetcher.R
import app.archivefetcher.addon.InstalledAddon

/** Manage the catalogs the app knows: add an addon by its address or from a file, update or remove the installed ones. */
@Composable
fun AddonsScreen(vm: MainViewModel) {
    val addons by vm.addons.collectAsState()
    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { urlFocus.requestFocusWhenReady() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.installAddonFromFile(uri)
    }
    ScreenFrame(
        title = stringResource(R.string.addons),
        subtitle = stringResource(R.string.addons_subtitle),
        onBack = { vm.back() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Panel(spacing = 10.dp) {
                Overline(stringResource(R.string.add_addon))
                TextEntry(
                    vm.addonUrl,
                    { vm.addonUrl = it },
                    stringResource(R.string.addon_url_hint),
                    Modifier.fillMaxWidth(),
                    label = stringResource(R.string.addon_url),
                    focusRequester = urlFocus,
                    keyboardType = KeyboardType.Uri,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        stringResource(R.string.add_addon_file),
                        { if (!vm.addonBusy) filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        Modifier.weight(1f),
                        icon = AppIcons.File,
                    )
                    AppButton(
                        stringResource(if (vm.addonBusy) R.string.addon_adding else R.string.add_short),
                        { vm.installAddon() },
                        Modifier.weight(1f),
                        highlighted = vm.addonUrl.isNotBlank(),
                        icon = AppIcons.Add,
                        enabled = !vm.addonBusy,
                    )
                }
                AnimatedVisibility(vm.addonBusy, enter = fadeIn() + expandVertically(), exit = fadeOut()) { IndeterminateBar() }
                vm.addonMessage?.let { message ->
                    Banner(
                        message.text,
                        icon = if (message.isError) AppIcons.Warning else AppIcons.Check,
                        tint = if (message.isError) Palette.Danger else Palette.Success,
                    )
                }
                Text(stringResource(R.string.addons_about), style = AppType.Caption)
            }

            if (addons.isEmpty()) {
                Text(stringResource(R.string.addons_none), style = AppType.Caption.copy(color = Palette.TextFaint))
            } else {
                Overline(stringResource(R.string.addons_installed))
                addons.forEach { addon ->
                    AddonCard(addon, onUpdate = { vm.updateAddon(addon.id) }, onRemove = { vm.removeAddon(addon.id) }, busy = vm.addonBusy)
                }
            }
        }
    }
}

@Composable
private fun AddonCard(addon: InstalledAddon, onUpdate: () -> Unit, onRemove: () -> Unit, busy: Boolean) {
    val manifest = addon.manifest
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Monogram(manifest.name, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(manifest.name, style = AppType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.addon_summary, manifest.version, manifest.consoleCount, manifest.collectionCount),
                    style = AppType.Caption,
                )
            }
        }
        manifest.description?.let { Text(it, style = AppType.Caption.copy(color = Palette.Text), maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Text(
            if (addon.canRefresh) addon.url else stringResource(R.string.addon_from_file),
            style = AppType.Caption.copy(color = Palette.TextFaint),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (addon.canRefresh) {
                AppButton(stringResource(R.string.addon_update), { if (!busy) onUpdate() }, Modifier.weight(1f), icon = AppIcons.Refresh, enabled = !busy)
            }
            AppButton(stringResource(R.string.remove), onRemove, Modifier.weight(1f), icon = AppIcons.Delete)
        }
    }
}
