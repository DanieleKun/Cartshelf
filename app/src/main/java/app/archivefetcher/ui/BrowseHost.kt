package app.archivefetcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Renders whichever screen is on top of the navigation stack, with the download strip underneath. */
@Composable
fun BrowseHost(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        val saved = rememberSaveableStateHolder()
        Box(Modifier.weight(1f)) {
            val screen = vm.current
            // Keyed by stack position, so going back to a list restores where it was scrolled.
            saved.SaveableStateProvider("${vm.stack.lastIndex}:$screen") {
            when (screen) {
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
        if (vm.current != Screen.Downloads) {
            DownloadStrip(vm, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
