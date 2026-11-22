package app.archivefetcher.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMergerTest {
    private fun addon(id: String, vararg categories: ManifestCategory) =
        AddonManifest(spec = 1, id = id, name = id, version = "1", categories = categories.toList())

    private fun category(id: String, name: String, vararg consoles: ManifestConsole) = ManifestCategory(id, name, consoles.toList())

    private fun console(id: String, name: String, vararg items: Pair<String, String>) =
        ManifestConsole(
            id, name,
            items.map { (title, item) -> ManifestCollection(title, ManifestSource(AddonSpec.SOURCE_ARCHIVE_ITEM, item)) },
        )

    @Test
    fun `the same console from two addons is merged into one`() {
        val first = addon("a", category("sony", "Sony", console("sony-psp", "Sony - PlayStation Portable", "First" to "item-1")))
        val second = addon("b", category("sony", "SONY", console("sony-psp", "PSP", "Second" to "item-2")))
        val catalog = CatalogMerger.merge(listOf(first, second))

        val psp = assertNotNull(catalog.console("sony-psp"))
        assertEquals("Sony - PlayStation Portable", psp.info.name)
        assertEquals(listOf("First", "Second"), psp.entries.map { it.title })
        assertEquals("Sony", catalog.category("sony")?.name)
        assertEquals("sony", psp.info.categoryId)
    }

    @Test
    fun `order follows the first addon that declared a category or console`() {
        val first = addon(
            "a",
            category("nintendo", "Nintendo", console("nintendo-nes", "NES", "x" to "i1"), console("nintendo-snes", "SNES", "x" to "i2")),
            category("sony", "Sony", console("sony-ps1", "PS1", "x" to "i3")),
        )
        val second = addon(
            "b",
            category("sega", "Sega", console("sega-saturn", "Saturn", "x" to "i4")),
            category("nintendo", "Nintendo", console("nintendo-gb", "GB", "x" to "i5")),
        )
        val catalog = CatalogMerger.merge(listOf(first, second))
        assertEquals(listOf("nintendo", "sony", "sega"), catalog.categories.map { it.id })
        assertEquals(listOf("nintendo-nes", "nintendo-snes", "nintendo-gb"), catalog.category("nintendo")!!.consoles.map { it.info.id })
    }

    @Test
    fun `the same item and path listed twice is kept once`() {
        val one = addon("a", category("sony", "Sony", console("sony-psp", "PSP", "Same" to "item")))
        val two = addon("b", category("sony", "Sony", console("sony-psp", "PSP", "Other title" to "item")))
        assertEquals(1, CatalogMerger.merge(listOf(one, two)).console("sony-psp")!!.entries.size)
    }

    @Test
    fun `different paths of one item are different collections`() {
        val manifest = addon(
            "a",
            category(
                "sega", "Sega",
                ManifestConsole(
                    "sega-saturn", "Saturn",
                    listOf(
                        ManifestCollection("USA", ManifestSource(AddonSpec.SOURCE_ARCHIVE_ITEM, "chd", "USA")),
                        ManifestCollection("EUR", ManifestSource(AddonSpec.SOURCE_ARCHIVE_ITEM, "chd", "Europe")),
                    ),
                ),
            ),
        )
        assertEquals(listOf("USA", "Europe"), CatalogMerger.merge(listOf(manifest)).console("sega-saturn")!!.entries.map { it.subPath })
    }

    @Test
    fun `consoles and categories without collections are left out`() {
        val manifest = addon(
            "a",
            category("empty", "Empty", console("empty-console", "Nothing")),
            category("sony", "Sony", console("sony-ps1", "PS1"), console("sony-ps2", "PS2", "x" to "item")),
        )
        val catalog = CatalogMerger.merge(listOf(manifest))
        assertNull(catalog.category("empty"))
        assertEquals(listOf("sony-ps2"), catalog.category("sony")!!.consoles.map { it.info.id })
    }

    @Test
    fun `no addons give an empty catalog`() {
        assertTrue(CatalogMerger.merge(emptyList()).isEmpty)
    }

    @Test
    fun `the format declared by the addon travels with the entry`() {
        val manifest = addon(
            "a",
            category(
                "sony", "Sony",
                ManifestConsole(
                    "sony-ps1", "PS1",
                    listOf(ManifestCollection("CHD", ManifestSource(AddonSpec.SOURCE_ARCHIVE_ITEM, "item"), format = "chd")),
                ),
            ),
        )
        assertEquals("chd", CatalogMerger.merge(listOf(manifest)).console("sony-ps1")!!.entries.single().format)
    }

    @Test
    fun `the expand setting travels with the entry`() {
        val manifest = addon(
            "a",
            category(
                "nintendo", "Nintendo",
                ManifestConsole(
                    "nintendo-gbc", "GBC",
                    listOf(ManifestCollection("GBC", ManifestSource(AddonSpec.SOURCE_ARCHIVE_ITEM, "item", expand = AddonSpec.EXPAND_ZIP))),
                ),
            ),
        )
        assertTrue(CatalogMerger.merge(listOf(manifest)).console("nintendo-gbc")!!.entries.single().expandZip)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
