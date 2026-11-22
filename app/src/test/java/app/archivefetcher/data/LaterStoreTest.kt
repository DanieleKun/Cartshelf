package app.archivefetcher.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaterStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = LaterStore(File(folder.root, "data/later.json"))

    private fun item(path: String, console: String = "nintendo-gbc") =
        LaterItem(console, "GBC", "item", path, 100, "abc", false, "zip", "Pack")

    @Test
    fun `added items survive a restart`() {
        store().add(listOf(item("a.zip"), item("b.zip")))
        val reopened = store()
        assertEquals(listOf("a.zip", "b.zip"), reopened.items.value.map { it.path })
        assertEquals("abc", reopened.items.value.first().md5)
    }

    @Test
    fun `the same file is not added twice`() {
        val store = store()
        assertEquals(2, store.add(listOf(item("a.zip"), item("b.zip"))))
        assertEquals(1, store.add(listOf(item("a.zip"), item("c.zip"))))
        assertEquals(3, store.items.value.size)
    }

    @Test
    fun `removing by key leaves the others`() {
        val store = store()
        store.add(listOf(item("a.zip"), item("b.zip")))
        store.remove(setOf("item/a.zip"))
        assertEquals(listOf("b.zip"), store.items.value.map { it.path })
        assertEquals(listOf("b.zip"), store().items.value.map { it.path })
    }

    @Test
    fun `a damaged file gives an empty list`() {
        val file = File(folder.root, "later.json").apply { writeText("{{{") }
        assertTrue(LaterStore(file).items.value.isEmpty())
    }

    @Test
    fun `a saved item rebuilds its region and language from the name`() {
        val rebuilt = item("Game (Europe) (En,Fr).zip").toRomFile()
        assertEquals(listOf("Europe"), rebuilt.regions)
        assertEquals(listOf("En", "Fr"), rebuilt.languages)
        assertEquals("Game (Europe) (En,Fr).zip", rebuilt.displayName)
    }
}
