package app.archivefetcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R

/** First level: manufacturers (Nintendo, Sony, Sega…). Everything comes from the installed addons. */
@Composable
fun CategoriesScreen(vm: MainViewModel) {
    val catalog by vm.catalog.collectAsState()
    val later by vm.later.collectAsState()
    ScreenFrame(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.choose_category),
        actions = {
            AppButton(
                if (later.isEmpty()) stringResource(R.string.later_short) else stringResource(R.string.later_n, later.size),
                { vm.open(Screen.Later) },
            )
            AppButton(stringResource(R.string.addons), { vm.open(Screen.Addons) }, highlighted = catalog.isEmpty)
            AppButton(stringResource(R.string.settings_short), { vm.open(Screen.Settings) })
        },
    ) {
        if (catalog.isEmpty) {
            EmptyCatalog { vm.open(Screen.Addons) }
        } else {
            val first = remember { FocusRequester() }
            LaunchedEffect(catalog) { first.requestFocusWhenReady() }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusSurface({ vm.open(Screen.Search) }, Modifier.fillMaxWidth(), cornerRadius = 12) {
                Text(
                    stringResource(R.string.search_hint_all),
                    color = Palette.TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(catalog.categories, key = { _, category -> category.id }) { index, category ->
                    FocusSurface(
                        onClick = { vm.open(Screen.Category(category.id)) },
                        focusRequester = if (index == 0) first else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(category.name, color = Palette.Text, fontSize = 16.sp, minLines = 1, maxLines = 2)
                            Text(
                                pluralStringResource(R.plurals.consoles_count, category.consoles.size, category.consoles.size),
                                color = Palette.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun EmptyCatalog(onAddAddon: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_catalog_title), color = Palette.Text, fontSize = 18.sp)
        Text(
            stringResource(R.string.empty_catalog_body),
            color = Palette.TextDim,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        AppButton(stringResource(R.string.add_addon), onAddAddon, highlighted = true, focusRequester = focus)
    }
}

/** Second level: the consoles of one manufacturer, one per row. */
@Composable
fun CategoryScreen(vm: MainViewModel, screen: Screen.Category) {
    val catalog by vm.catalog.collectAsState()
    val category = catalog.category(screen.categoryId)
    ScreenFrame(
        title = category?.name.orEmpty(),
        subtitle = category?.let { pluralStringResource(R.plurals.consoles_count, it.consoles.size, it.consoles.size) },
        onBack = { vm.back() },
    ) {
        if (category == null) {
            CenterMessage(stringResource(R.string.console_not_found))
        } else {
            val first = remember { FocusRequester() }
            LaunchedEffect(category.id) { first.requestFocusWhenReady() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(category.consoles, key = { _, console -> console.info.id }) { index, console ->
                    FocusSurface(
                        onClick = { vm.open(Screen.Console(console.info.id)) },
                        focusRequester = if (index == 0) first else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(console.info.name, color = Palette.Text, fontSize = 15.sp)
                            Text(
                                pluralStringResource(
                                    R.plurals.collections_count, console.entries.size, console.entries.size,
                                ),
                                color = Palette.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Accent)
    }
}
