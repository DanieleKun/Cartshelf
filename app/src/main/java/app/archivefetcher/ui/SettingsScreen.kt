package app.archivefetcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.archivefetcher.BuildConfig
import app.archivefetcher.R
import app.archivefetcher.data.Prefs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.graph.prefs
    var parallel by remember { mutableIntStateOf(prefs.maxParallel) }
    var language by remember { mutableStateOf(prefs.language) }

    var extract by remember { mutableStateOf(prefs.extractZips) }
    var deleteZip by remember { mutableStateOf(prefs.deleteZipAfterExtract) }
    var playlists by remember { mutableStateOf(prefs.createPlaylists) }
    var covers by remember { mutableStateOf(prefs.showCovers) }
    var verify by remember { mutableStateOf(prefs.verifyDownloads) }
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var notifications by remember { mutableStateOf(hasNotificationPermission(context)) }
    // The system dialogs run outside the app, so re-read the answers whenever the screen comes back.
    LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        notifications = hasNotificationPermission(context)
        onPauseOrDispose { }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    ScreenFrame(title = stringResource(R.string.settings), onBack = { vm.back() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section(stringResource(R.string.settings_language)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        R.string.language_system to Prefs.LANGUAGE_SYSTEM,
                        R.string.language_en to "en",
                        R.string.language_it to "it",
                    ).forEach { (label, code) ->
                        Chip(stringResource(label), language == code, firstFocus.takeIf { language == code }) {
                            language = code
                            applyLanguage(context, prefs, code)
                        }
                    }
                }
            }
            Section(stringResource(R.string.settings_library)) {
                LibraryFolderSection(vm)
            }
            Section(stringResource(R.string.settings_downloads)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_parallel), style = AppType.Body, modifier = Modifier.weight(1f))
                    AppButton("−", { parallel = (parallel - 1).coerceAtLeast(1); prefs.maxParallel = parallel })
                    Text("$parallel", style = AppType.Title)
                    AppButton("+", { parallel = (parallel + 1).coerceAtMost(Prefs.MAX_PARALLEL); prefs.maxParallel = parallel })
                }
                Hint(stringResource(R.string.settings_parallel_hint, Prefs.MAX_PARALLEL))
                OnOffRow(stringResource(R.string.extract_zips), extract) { extract = it; prefs.extractZips = it }
                if (extract) {
                    OnOffRow(stringResource(R.string.delete_zip), deleteZip) { deleteZip = it; prefs.deleteZipAfterExtract = it }
                }
                Hint(stringResource(R.string.extract_hint))
                OnOffRow(stringResource(R.string.create_playlists), playlists) { playlists = it; prefs.createPlaylists = it }
                Hint(stringResource(R.string.playlists_hint))
                OnOffRow(stringResource(R.string.verify_downloads), verify) { verify = it; prefs.verifyDownloads = it }
                Hint(stringResource(R.string.verify_hint))
            }
            Section(stringResource(R.string.settings_list)) {
                OnOffRow(stringResource(R.string.show_covers), covers) { covers = it; prefs.showCovers = it }
                Hint(stringResource(R.string.covers_hint))
            }
            Section(stringResource(R.string.settings_background)) {
                StatusRow(stringResource(R.string.background_notifications), notifications)
                if (unrestricted) {
                    StatusRow(stringResource(R.string.background_allowed), true)
                } else {
                    AppButton(stringResource(R.string.background_allow), { requestUnrestricted(context) }, Modifier.fillMaxWidth(), highlighted = true, icon = AppIcons.Battery)
                }
                Hint(stringResource(R.string.background_hint))
            }
            Section(stringResource(R.string.settings_account)) {
                ArchiveAccountSection(vm)
            }
            Section(stringResource(R.string.notice_title)) {
                Hint(stringResource(R.string.notice_body))
                Hint(stringResource(R.string.privacy_body))
            }
            Section(stringResource(R.string.about_title)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark(36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = AppType.Heading)
                        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = AppType.Caption)
                    }
                }
                AppButton(stringResource(R.string.about_source), { openLink(context, SOURCE_URL) }, Modifier.fillMaxWidth(), icon = AppIcons.Forward)
                AppUpdatesSection(vm)
                Hint(SOURCE_URL.removePrefix("https://"))
            }
        }
    }
}

@Composable
internal fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) AppIcons.Check else AppIcons.Info,
            contentDescription = null,
            tint = if (ok) Palette.Success else Palette.TextDim,
            modifier = Modifier.size(18.dp),
        )
        Text(label, style = AppType.Body)
    }
}

@Composable
internal fun OnOffRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = AppType.Body, modifier = Modifier.weight(1f))
        Chip(stringResource(R.string.option_on), value) { onChange(true) }
        Chip(stringResource(R.string.option_off), !value) { onChange(false) }
    }
}

/** Asks the system to exempt this app from battery optimization, falling back to the general settings list. */
@SuppressLint("BatteryLife")
internal fun requestUnrestricted(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.background_unavailable), Toast.LENGTH_LONG).show()
        }
    }
}

private fun applyLanguage(context: Context, prefs: Prefs, code: String) {
    if (prefs.language == code) return
    prefs.language = code
    (context as? Activity)?.recreate()
}

/** Opens a web page; on devices without a browser (some handhelds) the link is copied instead. */
internal fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(Uri.parse(url).host, url))
        Toast.makeText(context, context.getString(R.string.keys_link_copied), Toast.LENGTH_LONG).show()
    }
}

/** Where the source code and the releases live. */
private const val SOURCE_URL = "https://github.com/DanieleKun/Cartshelf"

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(title, Modifier)
        Panel(spacing = 10.dp, content = content)
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = AppType.Caption)
}
