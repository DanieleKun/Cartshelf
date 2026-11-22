package app.archivefetcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.archivefetcher.R
import app.archivefetcher.data.FileFilters
import app.archivefetcher.data.RomNaming
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersScreen(vm: MainViewModel) {
    val files = vm.library.files
    // Only options that exist in this console, in a fixed order; a group with a single option filters nothing.
    val regions = remember(files) {
        val present = files.flatMapTo(HashSet()) { it.regionGroups }
        RomNaming.regionGroups.filter { it in present }
    }
    val languages = remember(files) {
        val present = files.flatMapTo(HashSet()) { it.languageGroups }
        (RomNaming.mainLanguages + RomNaming.LANGUAGE_OTHER).filter { it in present }
    }
    val formats = remember(files) { files.map { it.format }.distinct().sorted() }
    val packs = remember(files) { files.map { it.packTitle }.distinct().sorted() }
    val matching = remember(files, vm.filters, vm.query, vm.present) { vm.visibleFiles(files).files.size }
    val first = remember { FocusRequester() }
    LaunchedEffect(regions, languages, formats, packs) { first.requestFocusWhenReady() }
    val firstGroup = when {
        languages.size > 1 -> 0
        regions.size > 1 -> 1
        formats.size > 1 -> 2
        packs.size > 1 -> 3
        else -> 4
    }

    ScreenFrame(
        title = stringResource(R.string.filters),
        subtitle = stringResource(R.string.files_count, matching),
        onBack = { vm.back() },
        actions = {
            if (vm.filters.activeCount > 0) AppButton(stringResource(R.string.filters_clear_short), { vm.filters = FileFilters() })
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.filters_hint), style = AppType.Caption)
            FilterGroup(
                stringResource(R.string.filter_language), languages, vm.filters.languages,
                { languageLabel(it) }, first.takeIf { firstGroup == 0 },
            ) { vm.filters = vm.filters.toggleLanguage(it) }
            FilterGroup(stringResource(R.string.filter_region), regions, vm.filters.regions, { regionLabel(it) }, first.takeIf { firstGroup == 1 }) {
                vm.filters = vm.filters.toggleRegion(it)
            }
            FilterGroup(stringResource(R.string.filter_format), formats, vm.filters.formats, { it.uppercase() }, first.takeIf { firstGroup == 2 }) {
                vm.filters = vm.filters.toggleFormat(it)
            }
            FilterGroup(stringResource(R.string.filter_pack), packs, vm.filters.packs, { it }, first.takeIf { firstGroup == 3 }) {
                vm.filters = vm.filters.togglePack(it)
            }
            if (vm.present.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline(stringResource(R.string.filter_present))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.filter_show_all), !vm.filters.hidePresent) { vm.filters = vm.filters.withHidePresent(false) }
                        Chip(
                            stringResource(R.string.filter_hide_present), vm.filters.hidePresent,
                            first.takeIf { firstGroup == 4 },
                        ) { vm.filters = vm.filters.withHidePresent(true) }
                    }
                }
            }
            AppButton(
                stringResource(R.string.filters_show, matching), { vm.back() }, Modifier.fillMaxWidth(),
                highlighted = true, icon = AppIcons.Check,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    options: List<String>,
    selected: Set<String>,
    label: @Composable (String) -> String,
    firstFocus: FocusRequester?,
    onToggle: (String) -> Unit,
) {
    if (options.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                Chip(label(option), option in selected, if (index == 0) firstFocus else null) { onToggle(option) }
            }
        }
    }
}

@Composable
private fun regionLabel(group: String): String = stringResource(
    when (group) {
        RomNaming.GROUP_USA -> R.string.region_usa
        RomNaming.GROUP_EUROPE -> R.string.region_europe
        RomNaming.GROUP_JAPAN -> R.string.region_japan
        RomNaming.GROUP_WORLD -> R.string.region_world
        RomNaming.GROUP_ASIA -> R.string.region_asia
        else -> R.string.region_other
    },
)

@Composable
private fun languageLabel(code: String): String {
    if (code == RomNaming.LANGUAGE_OTHER) return stringResource(R.string.language_other)
    val locale = LocalConfiguration.current.locales[0]
    val name = Locale.forLanguageTag(code.lowercase()).getDisplayLanguage(locale).replaceFirstChar { it.uppercase() }
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code else name
}
