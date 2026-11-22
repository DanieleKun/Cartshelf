package app.archivefetcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One entry of the navigation stack: its position tells whether a change is a step forward or back. */
private data class StackEntry(val depth: Int, val screen: Screen)

/**
 * Renders the screen on top of the navigation stack, with the download strip underneath. Going deeper slides the new
 * screen in from the right, going back slides it out; short and subtle so the D-pad never waits on an animation.
 */
@Composable
fun BrowseHost(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        val saved = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = StackEntry(vm.stack.lastIndex, vm.current),
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                val shift = { width: Int -> (width / SLIDE_FRACTION) * if (forward) 1 else -1 }
                (slideInHorizontally(tween(DURATION_MEDIUM)) { shift(it) } + fadeIn(tween(DURATION_MEDIUM))) togetherWith
                    (slideOutHorizontally(tween(DURATION_MEDIUM)) { -shift(it) } + fadeOut(tween(DURATION_SHORT)))
            },
            modifier = Modifier.weight(1f),
            label = "screens",
        ) { entry ->
            Box(Modifier.fillMaxSize()) {
                // Keyed by stack position, so going back to a list restores where it was scrolled.
                saved.SaveableStateProvider("${entry.depth}:${entry.screen}") {
                    when (val screen = entry.screen) {
                        Screen.Categories -> CategoriesScreen(vm)
                        is Screen.Category -> CategoryScreen(vm, screen)
                        is Screen.Console -> ConsoleScreen(vm, screen)
                        Screen.Filters -> FiltersScreen(vm)
                        Screen.Downloads -> DownloadsScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Addons -> AddonsScreen(vm)
                        Screen.Welcome -> WelcomeScreen(vm)
                        Screen.Search -> SearchScreen(vm)
                        Screen.Later -> LaterScreen(vm)
                    }
                }
            }
        }
        if (vm.current != Screen.Downloads && vm.current != Screen.Welcome) {
            DownloadStrip(vm, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

private const val SLIDE_FRACTION = 8
