package app.archivefetcher.addon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun raw(id: String, name: String = id) = """
        {"spec":1,"id":"$id","name":"$name","version":"1","categories":[
          {"id":"sony","name":"Sony","consoles":[{"id":"sony-psp-$id","name":"PSP","collections":[
            {"title":"T","source":{"type":"archive.org-item","id":"item"}}]}]}]}
    """.trimIndent()

    private fun installed(id: String, at: Long = 1L) =
        InstalledAddon(id, "https://example.com/$id.json", at, ManifestParser.parse(raw(id)))

    private fun store(): AddonStore = AddonStore(File(folder.root, "addons"))

    @Test
    fun `an empty store has no addons`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `a saved addon is loaded back with its address and time`() {
        val store = store()
        store.save(installed("first", at = 42L), raw("first"))
        val loaded = store().load().single()
        assertEquals("first", loaded.id)
        assertEquals("https://example.com/first.json", loaded.url)
        assertEquals(42L, loaded.fetchedAt)
        assertEquals(1, loaded.manifest.consoleCount)
    }

    @Test
    fun `saving the same id again replaces it in place`() {
        val store = store()
        store.save(installed("addon-a"), raw("addon-a"))
        store.save(installed("addon-b"), raw("addon-b"))
        store.save(installed("addon-a", at = 99L), raw("addon-a", name = "renamed"))

        val loaded = store.load()
        assertEquals(listOf("addon-a", "addon-b"), loaded.map { it.id })
        assertEquals(99L, loaded.first().fetchedAt)
        assertEquals("renamed", loaded.first().manifest.name)
    }

    @Test
    fun `removing an addon deletes its file and index entry`() {
        val store = store()
        store.save(installed("addon-a"), raw("addon-a"))
        store.save(installed("addon-b"), raw("addon-b"))
        store.remove("addon-a")

        assertEquals(listOf("addon-b"), store.load().map { it.id })
        assertFalse(File(folder.root, "addons/addon-a.json").exists())
    }

    @Test
    fun `a damaged manifest file is skipped without losing the others`() {
        val store = store()
        store.save(installed("good"), raw("good"))
        store.save(installed("broken"), raw("broken"))
        File(folder.root, "addons/broken.json").writeText("not json")

        assertEquals(listOf("good"), store.load().map { it.id })
    }

    @Test
    fun `a damaged index is treated as empty`() {
        val store = store()
        store.save(installed("addon-a"), raw("addon-a"))
        File(folder.root, "addons/index.json").writeText("{{{")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `an id that could escape the folder is refused`() {
        try {
            store().remove("../evil")
            fail("expected the id to be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
