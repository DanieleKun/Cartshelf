package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomNamingTest {
    @Test
    fun `extracts regions and format from file names`() {
        assertEquals(listOf("Europe", "Australia"), RomNaming.regionsOf("007 (Europe, Australia) (En,Fr).zip"))
        assertEquals(listOf("USA"), RomNaming.regionsOf("Game (USA).chd"))
        assertTrue(RomNaming.regionsOf("Game.iso").isEmpty())
        assertEquals("chd", RomNaming.formatOf("Game (USA).chd"))
    }

    @Test
    fun `extracts languages separately from regions`() {
        val name = "007 - From Russia with Love (Europe, Australia) (En,Fr,De,Es,It).zip"
        assertEquals(listOf("En", "Fr", "De", "Es", "It"), RomNaming.languagesOf(name))
        assertTrue(RomNaming.languagesOf("Game (USA).zip").isEmpty())
        assertTrue(RomNaming.languagesOf("Game (Rev 1) (Beta).zip").isEmpty())
    }

    @Test
    fun `file names from remote metadata are made safe`() {
        assertEquals("download", RomNaming.safeFileName(".."))
        assertEquals("download", RomNaming.safeFileName("  "))
        assertEquals("a_b_c.zip", RomNaming.safeFileName("a\\b:c.zip"))
        assertEquals("Game (USA).zip", RomNaming.safeFileName("Game (USA).zip"))
        assertTrue(RomNaming.safeFileName("x".repeat(500)).length <= 200)
    }

    @Test
    fun `format prefers the collection title over the archive extension`() {
        assertEquals("iso", RomNaming.formatOf("Game (USA).zip", "PSP Redump .iso (Part 1)"))
        assertEquals("chd", RomNaming.formatOf("Game (USA).chd", "PSP Redump .chd (Part 2)"))
        assertEquals("zip", RomNaming.formatOf("Game (USA).zip", "No-Intro collection"))
    }

    @Test
    fun `countries fold into a short list of region groups`() {
        assertEquals(
            listOf("Europe", "Asia", "Other", "USA"),
            RomNaming.regionGroupsOf(listOf("Germany", "France", "Korea", "Brazil", "Canada")),
        )
    }

    @Test
    fun `only real languages are kept and aliases are normalised`() {
        assertEquals(listOf("Ja", "Ko"), RomNaming.languagesOf("Game (Jp,Kr).zip"))
        assertEquals(listOf("En"), RomNaming.languagesOf("Game (En-GB).zip"))
        assertTrue(RomNaming.languagesOf("Game (Us).zip").isEmpty())
        assertTrue(RomNaming.languagesOf("Game (Pc-sig).zip").isEmpty())
        assertTrue(RomNaming.languagesOf("Game (Cd).zip").isEmpty())
    }

    @Test
    fun `minor languages are grouped as other`() {
        assertEquals(listOf("En", "Other"), RomNaming.languageGroupsOf(listOf("En", "Sv", "Fi")))
    }

    @Test
    fun `stem drops only a real extension`() {
        assertEquals("Game (USA)", RomNaming.stemOf("Game (USA).zip"))
        assertEquals("Game (USA)", RomNaming.stemOf("Game (USA).gba"))
        assertEquals("Mr. Driller (USA)", RomNaming.stemOf("Mr. Driller (USA).chd"))
        assertEquals("Mr. Driller", RomNaming.stemOf("Mr. Driller"))
    }
}
