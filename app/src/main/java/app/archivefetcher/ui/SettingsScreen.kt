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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.archivefetcher.R
import app.archivefetcher.data.LoginResult
import app.archivefetcher.data.Prefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.graph.prefs
    var parallel by remember { mutableIntStateOf(prefs.maxParallel) }
    var access by remember { mutableStateOf(prefs.accessKey) }
    var secret by remember { mutableStateOf(prefs.secretKey) }
    var language by remember { mutableStateOf(prefs.language) }

    var extract by remember { mutableStateOf(prefs.extractZips) }
    var deleteZip by remember { mutableStateOf(prefs.deleteZipAfterExtract) }
    var verify by remember { mutableStateOf(prefs.verifyDownloads) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loggedInAs by remember { mutableStateOf(prefs.loginEmail.takeIf { prefs.hasLogin }) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // The system dialog runs outside the app, so re-read the answer whenever the screen comes back.
    LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    ScreenFrame(
        title = stringResource(R.string.settings),
        onBack = { vm.back() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section(stringResource(R.string.settings_language)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Section(stringResource(R.string.settings_parallel)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("−", { parallel = (parallel - 1).coerceAtLeast(1); prefs.maxParallel = parallel })
                    Text("$parallel", color = Palette.Text, fontSize = 18.sp)
                    AppButton("+", { parallel = (parallel + 1).coerceAtMost(Prefs.MAX_PARALLEL); prefs.maxParallel = parallel })
                }
                Hint(stringResource(R.string.settings_parallel_hint, Prefs.MAX_PARALLEL))
            }
            Section(stringResource(R.string.settings_library)) {
                LibraryFolderSection(vm)
            }
            Section(stringResource(R.string.settings_extract)) {
                OnOffRow(stringResource(R.string.extract_zips), extract) { extract = it; prefs.extractZips = it }
                if (extract) {
                    OnOffRow(stringResource(R.string.delete_zip), deleteZip) { deleteZip = it; prefs.deleteZipAfterExtract = it }
                }
                Hint(stringResource(R.string.extract_hint))
            }
            Section(stringResource(R.string.settings_verify)) {
                OnOffRow(stringResource(R.string.verify_downloads), verify) { verify = it; prefs.verifyDownloads = it }
                Hint(stringResource(R.string.verify_hint))
            }
            Section(stringResource(R.string.settings_background)) {
                if (unrestricted) {
                    Text(stringResource(R.string.background_allowed), color = Palette.Accent, fontSize = 13.sp)
                } else {
                    AppButton(stringResource(R.string.background_allow), { requestUnrestricted(context) }, Modifier.fillMaxWidth(), highlighted = true)
                }
                Hint(stringResource(R.string.background_hint))
            }
            Section(stringResource(R.string.settings_account)) {
                AppButton(
                    stringResource(R.string.get_keys),
                    { openKeysPage(context) },
                    Modifier.fillMaxWidth(),
                    highlighted = true,
                )
                TextEntry(access, { access = it; prefs.accessKey = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(), label = stringResource(R.string.access_key))
                TextEntry(
                    secret, { secret = it; prefs.secretKey = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
                    label = stringResource(R.string.secret_key), secret = true,
                )
                Hint(stringResource(R.string.keys_hint))
            }
            Section(stringResource(R.string.settings_login)) {
                if (loggedInAs != null) {
                    Text(stringResource(R.string.login_ok, loggedInAs.orEmpty()), color = Palette.Accent, fontSize = 13.sp)
                    AppButton(stringResource(R.string.login_logout), { prefs.clearLogin(); loggedInAs = null }, Modifier.fillMaxWidth())
                } else {
                    TextEntry(email, { email = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(), label = stringResource(R.string.login_email))
                    TextEntry(
                        password, { password = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
                        label = stringResource(R.string.login_password), secret = true,
                    )
                    AppButton(
                        stringResource(if (loginBusy) R.string.login_busy else R.string.login_button),
                        {
                            if (!loginBusy && email.isNotBlank() && password.isNotEmpty()) {
                                loginBusy = true
                                loginMessage = null
                                scope.launch {
                                    when (val result = vm.graph.login.login(email.trim(), password)) {
                                        is LoginResult.Success -> {
                                            prefs.saveLogin(email.trim(), result.cookies)
                                            loggedInAs = email.trim()
                                            password = ""
                                        }
                                        is LoginResult.Failure -> {
                                            val reason = when {
                                                result.reason == "account_not_found" -> context.getString(R.string.login_err_account)
                                                result.reason.contains("password") -> context.getString(R.string.login_err_password)
                                                else -> result.reason
                                            }
                                            loginMessage = context.getString(R.string.login_failed, reason)
                                        }
                                    }
                                    loginBusy = false
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        highlighted = true,
                    )
                    loginMessage?.let { Text(it, color = Palette.Danger, fontSize = 13.sp) }
                }
                Hint(stringResource(R.string.login_hint))
                if (loggedInAs == null) Hint(stringResource(R.string.login_google_hint))
            }
            Section(stringResource(R.string.notice_title)) {
                Hint(stringResource(R.string.notice_body))
            }
        }
    }
}

@Composable
private fun OnOffRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Chip(stringResource(R.string.option_on), value) { onChange(true) }
        Chip(stringResource(R.string.option_off), !value) { onChange(false) }
    }
}

/** Asks the system to exempt this app from battery optimization, falling back to the general settings list. */
@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: Context) {
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

/** Opens the page where Internet Archive shows the user's keys; falls back to copying the link when no browser exists. */
private fun openKeysPage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.KEYS_PAGE_URL)))
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("archive.org", Prefs.KEYS_PAGE_URL))
        Toast.makeText(context, context.getString(R.string.keys_link_copied), Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.Accent, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 12.sp)
}
