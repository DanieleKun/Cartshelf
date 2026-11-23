package app.archivefetcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.archivefetcher.R
import app.archivefetcher.addon.CatalogCategory
import app.archivefetcher.data.UpdateInfo
import androidx.compose.ui.platform.LocalContext

/** The app's mark: a cartridge-orange tile with the download arrow. */
@Composable
fun BrandMark(size: Dp = 40.dp) {
    Box(Modifier.size(size).background(Palette.Accent, RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Download, contentDescription = null, tint = Palette.OnAccent, modifier = Modifier.size(size * 0.6f))
    }
}

/** Home: search across everything, then the platforms (Nintendo, Sony, Sega…) that the installed addons provide. */
@Composable
fun CategoriesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val catalog by vm.catalog.collectAsState()
    val later by vm.later.collectAsState()
    val indexed by vm.indexed.collectAsState()
    val consoleCount = remember(catalog) { catalog.categories.sumOf { it.consoles.size } }
    val indexedCount = remember(catalog, indexed) { catalog.categories.sumOf { c -> c.consoles.count { it.info.id in indexed } } }
    val first = remember { FocusRequester() }
    // At a cold start the window gets input focus a moment after the first frame; asking earlier is lost.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(catalog.isEmpty, windowFocused) { if (windowFocused) first.requestFocusWhenReady() }
    // Every return to the home screen may look for a new version; the checker keeps the requests rare.
    LifecycleResumeEffect(Unit) {
        vm.checkForUpdate()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark()
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = AppType.Title, maxLines = 1, modifier = Modifier.scrollIfLong(), overflow = TextOverflow.Clip)
                Text(
                    if (catalog.isEmpty) stringResource(R.string.home_subtitle_empty)
                    else pluralStringResource(R.plurals.consoles_count, consoleCount, consoleCount),
                    style = AppType.Caption,
                    maxLines = 1,
                )
            }
            IconAction(
                AppIcons.Clock, stringResource(R.string.later_title), { vm.open(Screen.Later) },
                badge = later.size.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() },
            )
            IconAction(AppIcons.Puzzle, stringResource(R.string.addons), { vm.open(Screen.Addons) }, highlighted = catalog.isEmpty)
            IconAction(AppIcons.Tune, stringResource(R.string.settings), { vm.open(Screen.Settings) })
        }

        vm.updateInfo?.let { info -> UpdateBanner(info, onOpen = { openLink(context, info.url) }, onDismiss = { vm.dismissUpdate() }) }

        if (catalog.isEmpty) {
            EmptyState(
                AppIcons.Puzzle,
                stringResource(R.string.empty_catalog_title),
                stringResource(R.string.empty_catalog_body),
                action = stringResource(R.string.add_addon),
                onAction = { vm.open(Screen.Addons) },
                actionFocus = first,
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(CATEGORY_CELL),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                SearchLauncher(indexedCount, consoleCount, first) { vm.open(Screen.Search) }
            }
            item(key = "platforms", span = { GridItemSpan(maxLineSpan) }) {
                Overline(stringResource(R.string.home_platforms), Modifier.padding(top = 6.dp, start = 2.dp))
            }
            itemsIndexed(catalog.categories, key = { _, category -> category.id }) { _, category ->
                CategoryCard(category) { vm.open(Screen.Category(category.id)) }
            }
        }
    }
}

private const val MAX_NOTES_LINES = 6

/** A newer version is on GitHub, with what is new in it: open its page to download it, or hide the notice for that version. */
@Composable
private fun UpdateBanner(info: UpdateInfo, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Panel(border = Palette.Accent.copy(alpha = 0.45f), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.Download, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_title, info.version), style = AppType.Label)
                Text(stringResource(R.string.update_body), style = AppType.Caption)
            }
        }
        if (info.notes.isNotBlank()) {
            Text(info.notes, style = AppType.Caption, maxLines = MAX_NOTES_LINES, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(stringResource(R.string.update_later), onDismiss, Modifier.weight(1f))
            AppButton(stringResource(R.string.update_open), onOpen, Modifier.weight(1f), highlighted = true, icon = AppIcons.Forward)
        }
    }
}

/** Looks like a search field; opens the search screen, where the real field is. */
@Composable
private fun SearchLauncher(indexed: Int, total: Int, focus: FocusRequester, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), focusRequester = focus, cornerRadius = 16) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.search_hint_all), style = AppType.Label, maxLines = 1, modifier = Modifier.scrollIfLong(), overflow = TextOverflow.Clip)
                Text(
                    if (indexed >= total) stringResource(R.string.home_search_ready)
                    else stringResource(R.string.home_search_partial, indexed, total),
                    style = AppType.Caption,
                    maxLines = 1, modifier = Modifier.scrollIfLong(),
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(category: CatalogCategory, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), cornerRadius = 16) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Monogram(category.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(category.name, style = AppType.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.consoles_count, category.consoles.size, category.consoles.size),
                    style = AppType.Caption,
                )
            }
        }
    }
}

/** Second level: the consoles of one maker, one per row. */
@Composable
fun CategoryScreen(vm: MainViewModel, screen: Screen.Category) {
    val catalog by vm.catalog.collectAsState()
    val indexed by vm.indexed.collectAsState()
    val category = catalog.category(screen.categoryId)
    ScreenFrame(
        title = category?.name.orEmpty(),
        subtitle = category?.let { pluralStringResource(R.plurals.consoles_count, it.consoles.size, it.consoles.size) },
        onBack = { vm.back() },
    ) {
        if (category == null) {
            EmptyState(AppIcons.Warning, stringResource(R.string.console_not_found), tint = Palette.Warning)
            return@ScreenFrame
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(category.id) { first.requestFocusWhenReady() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(category.consoles, key = { _, console -> console.info.id }) { index, console ->
                FocusSurface(
                    onClick = { vm.open(Screen.Console(console.info.id)) },
                    focusRequester = if (index == 0) first else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Monogram(console.info.name, size = 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(console.info.name, style = AppType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pluralStringResource(R.plurals.collections_count, console.entries.size, console.entries.size),
                                    style = AppType.Caption,
                                )
                                if (console.info.id in indexed) {
                                    Pill(stringResource(R.string.badge_searchable), Palette.Success, Palette.Success.copy(alpha = 0.12f))
                                }
                            }
                        }
                        Icon(AppIcons.ChevronRight, contentDescription = null, tint = Palette.TextFaint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

private val CATEGORY_CELL = 220.dp
