package app.archivefetcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R

/** Shown once on first launch: what the app is, where files go, and how to get a catalog. Everything can be skipped. */
@Composable
fun WelcomeScreen(vm: MainViewModel) {
    val addons by vm.addons.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
    ScreenFrame(title = stringResource(R.string.welcome_title), subtitle = stringResource(R.string.welcome_subtitle)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.welcome_intro), color = Palette.Text, fontSize = 14.sp)
            WelcomeStep(stringResource(R.string.welcome_step_responsibility)) {
                Text(stringResource(R.string.notice_body), color = Palette.TextDim, fontSize = 12.sp)
            }
            WelcomeStep(stringResource(R.string.welcome_step_folder)) {
                LibraryFolderSection(vm, firstFocus = first)
            }
            WelcomeStep(stringResource(R.string.welcome_step_addon)) {
                Text(stringResource(R.string.welcome_addon_body), color = Palette.TextDim, fontSize = 12.sp)
                if (addons.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.welcome_addons_installed, addons.size, addons.size),
                        color = Palette.Accent,
                        fontSize = 13.sp,
                    )
                }
                AppButton(stringResource(R.string.welcome_addon_button), { vm.open(Screen.Addons) }, Modifier.fillMaxWidth())
            }
            AppButton(stringResource(R.string.welcome_start), { vm.finishOnboarding() }, Modifier.fillMaxWidth(), highlighted = true)
            Text(stringResource(R.string.welcome_later), color = Palette.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WelcomeStep(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.Accent, fontSize = 13.sp)
        content()
    }
}
