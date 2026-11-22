package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    private fun matches(query: String, name: String) = SearchQuery.parse(query).matches(SearchText.normalize(name))
    private fun fuzzy(query: String, name: String) = SearchQuery.parse(query).matchesFuzzy(SearchText.normalize(name))

    @Test
    fun `normalize drops accents, case and punctuation`() {
        assertEquals("pokemon versione argento italy", SearchText.normalize("Pokémon - Versione Argento (Italy)"))
        assertEquals("legend of zelda the a link to the past", SearchText.normalize("Legend of Zelda, The: A Link to the Past"))
        assertEquals("strasse", SearchText.normalize("Straße"))
    }

    @Test
    fun `words match in any order and ignore separators`() {
        assertTrue(matches("pokemon versione argento", "Pokemon - Versione Argento (Italy).zip"))
        assertTrue(matches("POKÉMON argento", "Pokemon - Versione Argento (Italy).zip"))
        assertTrue(matches("argento pokemon", "Pokemon - Versione Argento (Italy).zip"))
        assertTrue(matches("zelda link past", "Legend of Zelda, The - A Link to the Past (Europe).zip"))
        assertTrue(matches("mario 3", "Super Mario Bros. 3 (USA).zip"))
        assertFalse(matches("pokemon oro", "Pokemon - Versione Argento (Italy).zip"))
    }

    @Test
    fun `words run together still match`() {
        assertTrue(matches("megaman", "Mega Man 2 (USA).zip"))
        assertTrue(matches("mega man", "MegaMan 2 (USA).zip"))
    }

    @Test
    fun `small typos are tolerated only in the fuzzy pass`() {
        assertFalse(matches("pokemon argneto", "Pokemon - Versione Argento (Italy).zip"))
        assertTrue(fuzzy("pokemon argneto", "Pokemon - Versione Argento (Italy).zip"))
        assertTrue(fuzzy("pokemno", "Pokemon Gold (USA).zip"))
        assertTrue(fuzzy("versione", "Pokemon - Version Argent (France).zip"))
        assertFalse(fuzzy("zelda", "Pokemon Gold (USA).zip"))
        assertFalse(fuzzy("ab", "Ac (USA).zip"))
    }

    @Test
    fun `the index search falls back to fuzzy results`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val store = IndexStore(dir)
            store.write("nintendo-gbc", listOf(RomFile.create("i", "Pokemon - Versione Argento (Italy).zip", 1, "zip", "P", false, null)))
            val exact = store.search("pokemon versione argento", 10, listOf("nintendo-gbc"))
            assertEquals(1, exact.total)
            assertFalse(exact.approximate)
            val typo = store.search("pokemon argnto", 10, listOf("nintendo-gbc"))
            assertEquals(1, typo.total)
            assertTrue(typo.approximate)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an index in the old layout is dropped`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            java.io.File(dir, "a-console.names").writeText("x\n")
            java.io.File(dir, "a-console.tsv").writeText("x\ti\tx.zip\t1\t\t0\tzip\tP\n")
            assertTrue(IndexStore(dir).indexed.value.isEmpty())
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
