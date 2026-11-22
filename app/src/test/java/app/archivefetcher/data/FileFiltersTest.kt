package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFiltersTest {
    private fun file(regions: List<String>, languages: List<String>, format: String = "iso", pack: String = "Pack A") =
        RomFile("id", "name.zip", 1, regions, languages, format, pack, locked = false)

    private val europeItalian = file(listOf("Europe"), listOf("En", "It"))
    private val usaEnglish = file(listOf("USA"), listOf("En"))
    private val japan = file(listOf("Japan"), emptyList(), format = "chd", pack = "Pack B")

    @Test
    fun `no filters keeps everything`() {
        assertTrue(listOf(europeItalian, usaEnglish, japan).all { FileFilters().matches(it) })
    }

    @Test
    fun `several values in one group are alternatives`() {
        val filters = FileFilters().toggleRegion("Europe").toggleRegion("USA")
        assertTrue(filters.matches(europeItalian))
        assertTrue(filters.matches(usaEnglish))
        assertFalse(filters.matches(japan))
    }

    @Test
    fun `different groups must all match`() {
        val filters = FileFilters().toggleRegion("Europe").toggleLanguage("It")
        assertTrue(filters.matches(europeItalian))
        assertFalse(filters.matches(usaEnglish))
    }

    @Test
    fun `toggling twice removes the value`() {
        val filters = FileFilters().toggleFormat("chd").toggleFormat("chd")
        assertEquals(0, filters.activeCount)
    }

    @Test
    fun `format filter`() {
        assertTrue(FileFilters().toggleFormat("chd").matches(japan))
        assertFalse(FileFilters().toggleFormat("chd").matches(usaEnglish))
    }

    @Test
    fun `pack filter`() {
        assertTrue(FileFilters().togglePack("Pack B").matches(japan))
        assertFalse(FileFilters().togglePack("Pack B").matches(usaEnglish))
    }

    @Test
    fun `countries are matched through their region group`() {
        val germany = file(listOf("Germany"), listOf("De"))
        assertTrue(FileFilters().toggleRegion("Europe").matches(germany))
        assertFalse(FileFilters().toggleRegion("USA").matches(germany))
        assertTrue(FileFilters().toggleRegion("Other").matches(file(listOf("Brazil"), listOf("Pt"))))
    }

    @Test
    fun `minor languages are matched through the other group`() {
        val swedish = file(listOf("Europe"), listOf("Sv"))
        assertTrue(FileFilters().toggleLanguage("Other").matches(swedish))
        assertFalse(FileFilters().toggleLanguage("En").matches(swedish))
    }

    @Test
    fun `hiding present files counts as an active filter`() {
        assertEquals(1, FileFilters().withHidePresent(true).activeCount)
    }
}
