package app.archivefetcher.ui

import android.content.Context
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.archivefetcher.R
import app.archivefetcher.data.LoginResult
import app.archivefetcher.data.Prefs
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The Internet Archive account, needed only for collections that require one. One way is enough: logging in with
 * email and password (the simplest), or the S3 keys for accounts that sign in with Google and have no password.
 * Every change answers with a visible confirmation.
 */
@Composable
internal fun ArchiveAccountSection(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.graph.prefs
    val scope = rememberCoroutineScope()
    var loggedInAs by remember { mutableStateOf(prefs.loginEmail.takeIf { prefs.hasLogin }) }
    var hasKeys by remember { mutableStateOf(prefs.hasCredentials) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    // The session can be cleared elsewhere (an expired login), so the screen reads the saved state again on return.
    LifecycleResumeEffect(Unit) {
        loggedInAs = prefs.loginEmail.takeIf { prefs.hasLogin }
        hasKeys = prefs.hasCredentials
        onPauseOrDispose { }
    }

    fun confirm(message: Int) {
        error = null
        done = context.getString(message)
    }

    Hint(stringResource(R.string.account_intro))
    if (loggedInAs != null) {
        StatusRow(stringResource(R.string.login_ok, loggedInAs.orEmpty()), true)
        AppButton(stringResource(R.string.login_logout), {
            prefs.clearLogin()
            loggedInAs = null
            confirm(R.string.login_logged_out)
        }, Modifier.fillMaxWidth())
    } else if (!hasKeys) {
        TextEntry(
            email, { email = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
            label = stringResource(R.string.login_email), keyboardType = KeyboardType.Email,
        )
        TextEntry(
            password, { password = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
            label = stringResource(R.string.login_password), secret = true,
        )
        AppButton(
            stringResource(if (busy) R.string.login_busy else R.string.login_button),
            {
                when {
                    busy -> Unit
                    email.isBlank() || password.isEmpty() -> error = context.getString(R.string.login_missing)
                    else -> {
                        busy = true
                        done = null
                        error = null
                        scope.launch {
                            try {
                                when (val result = vm.graph.login.login(email.trim(), password)) {
                                    is LoginResult.Success -> {
                                        prefs.saveLogin(email.trim(), result.cookies)
                                        loggedInAs = email.trim()
                                        password = ""
                                        confirm(R.string.login_saved)
                                    }
                                    is LoginResult.Failure -> error = context.getString(R.string.login_failed, loginReason(context, result.reason))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = context.getString(R.string.login_failed, e.message ?: e.javaClass.simpleName)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            },
            Modifier.fillMaxWidth(),
            highlighted = true,
            enabled = !busy,
            icon = AppIcons.Lock,
        )
    }

    if (hasKeys) {
        StatusRow(stringResource(R.string.keys_saved_status), true)
        AppButton(stringResource(R.string.keys_remove), {
            prefs.accessKey = ""
            prefs.secretKey = ""
            hasKeys = false
            confirm(R.string.keys_removed)
        }, Modifier.fillMaxWidth())
    } else if (loggedInAs == null) {
        if (!showKeys) {
            AppButton(stringResource(R.string.keys_use), { showKeys = true }, Modifier.fillMaxWidth())
        } else {
            Hint(stringResource(R.string.keys_hint))
            AppButton(stringResource(R.string.get_keys), { openLink(context, Prefs.KEYS_PAGE_URL) }, Modifier.fillMaxWidth(), icon = AppIcons.Forward)
            TextEntry(
                access, { access = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
                label = stringResource(R.string.access_key), secret = true,
            )
            TextEntry(
                secret, { secret = it }, stringResource(R.string.tap_to_edit), Modifier.fillMaxWidth(),
                label = stringResource(R.string.secret_key), secret = true,
            )
            AppButton(stringResource(R.string.keys_save), {
                if (access.isBlank() || secret.isBlank()) {
                    error = context.getString(R.string.keys_incomplete)
                    done = null
                } else {
                    prefs.accessKey = access.trim()
                    prefs.secretKey = secret.trim()
                    access = ""
                    secret = ""
                    hasKeys = true
                    showKeys = false
                    confirm(R.string.keys_saved)
                }
            }, Modifier.fillMaxWidth(), highlighted = true, icon = AppIcons.Check)
        }
    }

    done?.let { DoneNote(it) }
    error?.let { Banner(it, icon = AppIcons.Warning, tint = Palette.Danger) }
    if (loggedInAs == null && !hasKeys) Hint(stringResource(R.string.login_google_hint))
    Hint(stringResource(R.string.account_privacy))
}

private fun loginReason(context: Context, reason: String): String = when {
    reason == "account_not_found" -> context.getString(R.string.login_err_account)
    reason.contains("password") -> context.getString(R.string.login_err_password)
    else -> reason
}
