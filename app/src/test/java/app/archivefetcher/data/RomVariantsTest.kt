package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomVariantsTest {
    private fun rom(name: String, pack: String = "Pack", format: String = "zip") = RomFile.create("item", name, 100, format, pack, false, null)

    @Test
    fun `unfinished and unofficial releases are recognised`() {
        assertEquals(setOf(RomVariants.Flag.Unfinished), RomVariants.flagsOf("Game (USA) (Beta).zip"))
        assertEquals(setOf(RomVariants.Flag.Unfinished), RomVariants.flagsOf("Game (Japan) (Proto 2).zip"))
        assertEquals(setOf(RomVariants.Flag.Unfinished), RomVariants.flagsOf("Game (Europe) (Demo).zip"))
        assertEquals(setOf(RomVariants.Flag.Unofficial), RomVariants.flagsOf("101 Dalmatas (USA) (Pirate).zip"))
        assertEquals(setOf(RomVariants.Flag.Unofficial), RomVariants.flagsOf("Game (World) (Unl).zip"))
        assertEquals(setOf(RomVariants.Flag.Unofficial), RomVariants.flagsOf("Game (U) [b1].gb"))
        assertTrue(RomVariants.flagsOf("Game (Europe) (En,Fr) (Rev 1).zip").isEmpty())
        assertTrue(RomVariants.flagsOf("Betamax Story (USA).zip").isEmpty())
        assertTrue(RomVariants.flagsOf("Game (U) [a1].gb").isEmpty())
        assertTrue(RomVariants.flagsOf("Game (U) [f1].gb").isEmpty())
    }

    @Test
    fun `the best version follows language, region and revision`() {
        val files = listOf(
            rom("Super Game (USA).zip"), rom("Super Game (Europe) (En,Fr,De).zip"), rom("Super Game (Italy).zip"),
            rom("Super Game (Japan).zip"), rom("Super Game (USA) (Beta).zip"),
            rom("Other Game (USA).zip"), rom("Other Game (USA) (Rev 1).zip"), rom("Other Game (Japan).zip"),
        )
        assertEquals(listOf("Super Game (Italy).zip", "Other Game (USA) (Rev 1).zip"), RomVariants.bestPerTitle(files, "It").map { it.displayName })
        assertEquals(listOf("Super Game (USA).zip", "Other Game (USA) (Rev 1).zip"), RomVariants.bestPerTitle(files, "En").map { it.displayName })
        assertEquals(listOf("Super Game (Japan).zip", "Other Game (Japan).zip"), RomVariants.bestPerTitle(files, "Ja").map { it.displayName })
    }

    @Test
    fun `the newest version wins when nothing else differs`() {
        val files = listOf(rom("Game (USA) (v1.9).zip"), rom("Game (USA) (v1.10).zip"), rom("Game (USA).zip"))
        assertEquals("Game (USA) (v1.10).zip", RomVariants.bestPerTitle(files, "En").single().displayName)
    }

    @Test
    fun `a multi-language european release beats an english-only one for italians`() {
        val files = listOf(rom("Game (USA).zip"), rom("Game (Europe) (En,Fr,De,Es,It).zip"))
        assertEquals("Game (Europe) (En,Fr,De,Es,It).zip", RomVariants.bestPerTitle(files, "It").single().displayName)
    }

    @Test
    fun `every disc and format of the chosen release is kept`() {
        val files = listOf(
            rom("Saga (USA) (Disc 1).chd", format = "chd"), rom("Saga (USA) (Disc 2).chd", format = "chd"),
            rom("Saga (Japan) (Disc 1).chd", format = "chd"), rom("Saga (Japan) (Disc 2).chd", format = "chd"),
        )
        val kept = RomVariants.bestPerTitle(files, "En").map { it.displayName }
        assertEquals(listOf("Saga (USA) (Disc 1).chd", "Saga (USA) (Disc 2).chd"), kept)
    }

    @Test
    fun `revisions are read in their usual forms`() {
        assertEquals(listOf(2), RomVariants.revisionOf("Game (USA) (Rev 2).zip"))
        assertEquals(listOf(1), RomVariants.revisionOf("Game (USA) (Rev A).zip"))
        assertEquals(emptyList<Int>(), RomVariants.revisionOf("Game (USA).zip"))
        val v19 = RomVariants.revisionOf("Game (USA) (v1.9).zip")
        val v110 = RomVariants.revisionOf("Game (USA) (v1.10).zip")
        val v20 = RomVariants.revisionOf("Game (USA) (v2.0).zip")
        assertTrue(RomVariants.compareVersions(v110, v19) > 0)
        assertTrue(RomVariants.compareVersions(v20, v110) > 0)
        assertFalse(RomVariants.compareVersions(v19, emptyList()) <= 0)
    }

    @Test
    fun `quick filters hide unfinished and unofficial files`() {
        val beta = rom("Game (USA) (Beta).zip")
        val pirate = rom("Game (USA) (Pirate).zip")
        val good = rom("Game (USA).zip")
        val filters = FileFilters(hideUnfinished = true, hideUnofficial = true)
        assertEquals(listOf(good), listOf(beta, pirate, good).filter(filters::matches))
        assertEquals(3, filters.copy(oneGamePerTitle = true).activeCount)
        assertEquals(FileFilters(hideUnfinished = true, hideUnofficial = true), filters.copy(regions = setOf("USA")).cleared())
    }

    @Test
    fun `multi-disc releases fold into one entry and keep their place`() {
        val files = listOf(
            rom("Alpha (USA).zip"),
            rom("Saga (USA) (Disc 1).chd", format = "chd"), rom("Saga (USA) (Disc 2).chd", format = "chd"),
            rom("Saga (USA) (Disc 1).zip"), rom("Zeta (USA) (Disc 1).zip"),
        )
        val entries = GameEntries.group(files)
        assertEquals(listOf("Alpha (USA).zip", "Saga (USA)", "Saga (USA) (Disc 1).zip", "Zeta (USA) (Disc 1).zip"), entries.map { it.title })
        assertEquals(2, entries[1].files.size)
        assertEquals(200L, entries[1].totalSize)
        assertTrue(entries[1].isMultiDisc)
    }
}
