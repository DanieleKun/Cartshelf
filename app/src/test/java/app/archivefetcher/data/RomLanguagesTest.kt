package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomLanguagesTest {
    private fun languages(name: String) = RomFile.create("item", name, 1, "zip", "Pack", false, null).languages

    @Test
    fun `an explicit language list wins over the region`() {
        assertEquals(listOf("En", "Fr", "De", "Es", "It"), languages("007 (Europe) (En,Fr,De,Es,It).zip"))
        assertEquals(listOf("En"), languages("Game (Germany) (En).zip"))
    }

    @Test
    fun `a region without a language list implies its language`() {
        assertEquals(listOf("It"), languages("Pokemon - Versione Argento (Italy).zip"))
        assertEquals(listOf("En"), languages("Game (USA).zip"))
        assertEquals(listOf("Ja"), languages("Game (Japan).zip"))
        assertEquals(listOf("Fr"), languages("Jeu (France).zip"))
    }

    @Test
    fun `GoodTools region codes are understood`() {
        assertEquals(listOf("Italy"), RomNaming.regionsOf("Zelda (I).gb"))
        assertEquals(listOf("It"), languages("Zelda (I).gb"))
        assertEquals(listOf("Japan", "USA", "Europe"), RomNaming.regionsOf("Tetris (JUE).gb"))
    }

    @Test
    fun `language words and tags are recognised`() {
        assertEquals(listOf("It"), languages("Final Fantasy (Italian).iso"))
        assertEquals(listOf("It"), languages("Chrono Trigger [T-Ita].sfc"))
        assertEquals(listOf("It"), languages("Pokemon Rosso ITA.gba"))
        assertEquals(listOf("It"), languages("Gioco [ita].zip"))
        assertEquals(listOf("It"), languages("Monkey Island Italiano.zip"))
        assertEquals(listOf("En", "It"), languages("Game (Eng+Ita).zip"))
        assertEquals(listOf("De"), languages("Spiel (Deutsch).zip"))
    }

    @Test
    fun `words inside titles are not mistaken for languages`() {
        assertTrue(languages("It Came from the Desert.zip").isEmpty())
        assertTrue(languages("Italian Job, The.zip").none { it != "It" })
        assertTrue(languages("Game (Rev 1) (Beta).zip").isEmpty())
        assertTrue(languages("Bomberman (Disc 1).zip").isEmpty())
    }

    @Test
    fun `the italian filter finds every italian variant`() {
        val italian = FileFilters(languages = setOf("It"))
        listOf(
            "Pokemon - Versione Argento (Italy).zip", "Game (En,Fr,De,Es,It).zip", "Game (Italian).zip",
            "Game [T-Ita].zip", "Game ITA.zip", "Zelda (I).gb", "Game (It).zip",
        ).forEach { name ->
            assertTrue(name, italian.matches(RomFile.create("item", name, 1, "zip", "Pack", false, null)))
        }
        assertTrue(!italian.matches(RomFile.create("item", "Game (USA).zip", 1, "zip", "Pack", false, null)))
    }
}
