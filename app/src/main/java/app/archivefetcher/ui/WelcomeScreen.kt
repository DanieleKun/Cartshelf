package app.archivefetcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.archivefetcher.R

/** The first-run pages, one decision each. Every page can be passed over; everything can be changed later in Options. */
private enum class WelcomePage { Intro, Responsibility, Folder, Layout, Addon, Background, Ready }

@Composable
fun WelcomeScreen(vm: MainViewModel) {
    var index by rememberSaveable { mutableStateOf(0) }
    val pages = WelcomePage.entries
    val page = pages[index]
    val next = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(index, windowFocused) { if (windowFocused) next.requestFocusWhenReady() }
    // The system back key steps back through the pages before it leaves the app.
    BackHandler(enabled = index > 0) { index-- }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageDots(index, pages.size, Modifier.weight(1f))
            if (page != WelcomePage.Ready) {
                AppButton(stringResource(R.string.welcome_skip), { vm.finishOnboarding() })
            }
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(DURATION_MEDIUM)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(DURATION_MEDIUM))) togetherWith
                    fadeOut(tween(DURATION_SHORT))
            },
            modifier = Modifier.weight(1f),
            label = "welcomePage",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                when (pages[shown]) {
                    WelcomePage.Intro -> IntroPage()
                    WelcomePage.Responsibility -> InfoPage(AppIcons.Shield, R.string.welcome_resp_title, R.string.welcome_resp_body)
                    WelcomePage.Folder -> ChoicePage(AppIcons.Folder, R.string.welcome_folder_title, R.string.welcome_folder_body) {
                        LibraryFolderPicker(vm)
                    }
                    WelcomePage.Layout -> ChoicePage(AppIcons.Gamepad, R.string.welcome_layout_title, R.string.welcome_layout_body) {
                        FolderLayoutPicker(vm)
                    }
                    WelcomePage.Addon -> ChoicePage(AppIcons.Puzzle, R.string.welcome_addon_title, R.string.welcome_addon_body) {
                        AddonStep(vm)
                    }
                    WelcomePage.Background -> ChoicePage(AppIcons.Bell, R.string.welcome_bg_title, R.string.welcome_bg_body) {
                        BackgroundStep()
                    }
                    WelcomePage.Ready -> InfoPage(AppIcons.Check, R.string.welcome_ready_title, R.string.welcome_ready_body)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) {
                AppButton(stringResource(R.string.welcome_back), { index-- }, Modifier.weight(1f), icon = AppIcons.Back)
            } else {
                Spacer(Modifier.weight(1f))
            }
            val last = index == pages.lastIndex
            AppButton(
                stringResource(
                    when (page) {
                        WelcomePage.Responsibility -> R.string.welcome_understood
                        WelcomePage.Ready -> R.string.welcome_start
                        else -> R.string.welcome_next
                    },
                ),
                { if (last) vm.finishOnboarding() else index++ },
                Modifier.weight(1f),
                highlighted = true,
                focusRequester = next,
                icon = if (last) AppIcons.Check else AppIcons.Forward,
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, tween(DURATION_MEDIUM), label = "dotWidth")
            val color by animateColorAsState(
                if (i == current) Palette.Accent else if (i < current) Palette.Accent.copy(alpha = 0.45f) else Palette.SurfaceFocus,
                tween(DURATION_MEDIUM),
                label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun PageHeader(icon: ImageVector, title: Int, body: Int) {
    Box(Modifier.size(72.dp).background(Palette.AccentSoft, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(36.dp))
    }
    Text(stringResource(title), style = AppType.Title, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
    Text(stringResource(body), style = AppType.BodyDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
}

@Composable
private fun IntroPage() {
    PageColumn {
        BrandMark(72.dp)
        Text(stringResource(R.string.welcome_title), style = AppType.Display, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.welcome_intro),
            style = AppType.BodyDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
    }
}

@Composable
private fun InfoPage(icon: ImageVector, title: Int, body: Int) {
    PageColumn { PageHeader(icon, title, body) }
}

@Composable
private fun ChoicePage(icon: ImageVector, title: Int, body: Int, choice: @Composable () -> Unit) {
    PageColumn {
        PageHeader(icon, title, body)
        Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(top = 4.dp)) { choice() }
    }
}

@Composable
private fun AddonStep(vm: MainViewModel) {
    val addons by vm.addons.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (addons.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppIcons.Check, contentDescription = null, tint = Palette.Success, modifier = Modifier.size(18.dp))
                Text(pluralStringResource(R.plurals.welcome_addons_installed, addons.size, addons.size), style = AppType.Label.copy(color = Palette.Success))
            }
        }
        AppButton(stringResource(R.string.welcome_addon_button), { vm.open(Screen.Addons) }, Modifier.fillMaxWidth(), icon = AppIcons.Add)
    }
}

/** Notifications (to see download progress) and the battery exemption (so the screen can turn off). */
@Composable
private fun BackgroundStep() {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(hasNotificationPermission(context)) }
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    LifecycleResumeEffect(Unit) {
        notifications = hasNotificationPermission(context)
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepRow(AppIcons.Bell, stringResource(R.string.welcome_bg_notifications), notifications) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        StepRow(AppIcons.Battery, stringResource(R.string.welcome_bg_battery), unrestricted) { requestUnrestricted(context) }
    }
}

@Composable
private fun StepRow(icon: ImageVector, label: String, done: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), enabled = !done) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (done) Palette.Success else Palette.Accent, modifier = Modifier.size(22.dp))
            Text(label, style = AppType.Label, modifier = Modifier.weight(1f))
            if (done) {
                Icon(AppIcons.Check, contentDescription = null, tint = Palette.Success, modifier = Modifier.size(20.dp))
            } else {
                Text(stringResource(R.string.welcome_allow), style = AppType.Label.copy(color = Palette.Accent))
            }
        }
    }
}

internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
