package app.archivefetcher.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = IndexStore(File(folder.root, "index"))

    private fun rom(path: String, md5: String? = null, locked: Boolean = false) =
        RomFile.create("item", path, 1234, "zip", "Pack", locked, md5)

    @Test
    fun `every word of the query must be in the name`() {
        val store = store()
        store.write("nintendo-gbc", listOf(rom("Pokemon Gold (USA).zip"), rom("Pokemon Silver (USA).zip"), rom("Tetris (World).zip")))
        val order = listOf("nintendo-gbc")

        assertEquals(2, store.search("pokemon", 10, order).total)
        assertEquals(1, store.search("pokemon gold", 10, order).total)
        assertEquals(0, store.search("zelda", 10, order).total)
    }

    @Test
    fun `search is case insensitive and returns the stored fields`() {
        val store = store()
        store.write("sony-psp", listOf(rom("Wipeout Pure (Europe).zip", md5 = "abc123", locked = true)))
        val hit = store.search("wipeout", 10, listOf("sony-psp")).hits.single()

        assertEquals("sony-psp", hit.consoleId)
        assertEquals("Wipeout Pure (Europe).zip", hit.file.displayName)
        assertEquals("abc123", hit.file.md5)
        assertTrue(hit.file.locked)
        assertEquals(listOf("Europe"), hit.file.regions)
        assertEquals(1234L, hit.file.size)
    }

    @Test
    fun `the limit caps the hits but not the total`() {
        val store = store()
        store.write("x-console", (1..50).map { rom("Game $it.zip") })
        val result = store.search("game", 10, listOf("x-console"))
        assertEquals(10, result.hits.size)
        assertEquals(50, result.total)
    }

    @Test
    fun `consoles are searched in the given order and can be restricted`() {
        val store = store()
        store.write("a-console", listOf(rom("Alpha one.zip")))
        store.write("b-console", listOf(rom("Alpha two.zip")))
        val all = store.search("alpha", 10, listOf("b-console", "a-console"))
        assertEquals(listOf("b-console", "a-console"), all.hits.map { it.consoleId })
        val onlyA = store.search("alpha", 10, listOf("b-console", "a-console"), only = setOf("a-console"))
        assertEquals(listOf("a-console"), onlyA.hits.map { it.consoleId })
    }

    @Test
    fun `the indexed consoles are remembered and can be cleared`() {
        val store = store()
        store.write("a-console", listOf(rom("x.zip")))
        assertEquals(setOf("a-console"), store().indexed.value)
        store.clear()
        assertTrue(store.indexed.value.isEmpty())
        assertEquals(0, store.search("x", 10, listOf("a-console")).total)
    }

    @Test
    fun `names with tabs or line breaks cannot break the file`() {
        val store = store()
        store.write("a-console", listOf(rom("Odd\tname\nhere.zip"), rom("Normal.zip")))
        assertEquals(1, store.search("odd here", 10, listOf("a-console")).total)
        assertFalse(store.search("normal", 10, listOf("a-console")).hits.isEmpty())
    }

    @Test
    fun `a console id that could escape the folder is refused`() {
        try {
            store().write("../evil", emptyList())
            fail("expected the id to be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `an empty query matches nothing`() {
        val store = store()
        store.write("a-console", listOf(rom("x.zip")))
        assertEquals(0, store.search("", 10, listOf("a-console")).total)
    }
}
