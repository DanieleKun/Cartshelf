package app.archivefetcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import app.archivefetcher.addon.InstalledAddon

/** Manage the catalogs the app knows: add an addon by its address, update or remove the installed ones. */
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.add_addon), color = Palette.Accent, fontSize = 13.sp)
                TextEntry(
                    vm.addonUrl,
                    { vm.addonUrl = it },
                    stringResource(R.string.tap_to_edit),
                    Modifier.fillMaxWidth(),
                    label = stringResource(R.string.addon_url),
                    focusRequester = urlFocus,
                )
                AppButton(
                    stringResource(if (vm.addonBusy) R.string.addon_adding else R.string.add_addon),
                    { vm.installAddon() },
                    Modifier.fillMaxWidth(),
                    highlighted = vm.addonUrl.isNotBlank(),
                )
                AppButton(
                    stringResource(R.string.add_addon_file),
                    { if (!vm.addonBusy) filePicker.launch(arrayOf("application/json", "text/plain")) },
                    Modifier.fillMaxWidth(),
                )
                vm.addonMessage?.let { message ->
                    Text(message.text, color = if (message.isError) Palette.Danger else Palette.Accent, fontSize = 13.sp)
                }
                Text(stringResource(R.string.addons_about), color = Palette.TextDim, fontSize = 12.sp)
            }

            if (addons.isNotEmpty()) {
                Text(stringResource(R.string.addons_installed), color = Palette.Accent, fontSize = 13.sp)
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(manifest.name, color = Palette.Text, fontSize = 15.sp)
        Text(
            stringResource(R.string.addon_summary, manifest.version, manifest.consoleCount, manifest.collectionCount),
            color = Palette.Accent,
            fontSize = 12.sp,
        )
        manifest.description?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Text(
            if (addon.canRefresh) addon.url else stringResource(R.string.addon_from_file),
            color = Palette.TextDim,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (addon.canRefresh) {
                AppButton(stringResource(if (busy) R.string.addon_adding else R.string.addon_update), { if (!busy) onUpdate() }, Modifier.weight(1f))
            }
            AppButton(stringResource(R.string.remove), onRemove, Modifier.weight(1f))
        }
    }
}
