package app.archivefetcher.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestParserTest {
    private fun manifest(
        spec: String = "1",
        id: String = "\"my-addon\"",
        consoles: String = """
            {"id":"sony-psp","name":"Sony - PlayStation Portable","collections":[
              {"title":"PSP .iso (Part 1)","format":"iso","source":{"type":"archive.org-item","id":"item-one","path":"roms"}},
              {"title":"Second","source":{"type":"archive.org-item","id":"item-two"}}
            ]}
        """.trimIndent(),
        extra: String = "",
    ) = """
        {"spec":$spec,"id":$id,"name":"My addon","version":"1.0"$extra,
         "categories":[{"id":"sony","name":"Sony","consoles":[$consoles]}]}
    """.trimIndent()

    private fun failure(text: String): AddonException =
        try {
            ManifestParser.parse(text)
            fail("expected the manifest to be rejected")
            error("unreachable")
        } catch (e: AddonException) {
            e
        }

    @Test
    fun `a valid manifest is read completely`() {
        val parsed = ManifestParser.parse(manifest())
        assertEquals("my-addon", parsed.id)
        assertEquals(1, parsed.consoleCount)
        assertEquals(2, parsed.collectionCount)
        val first = parsed.categories.single().consoles.single().collections.first()
        assertEquals("iso", first.format)
        assertEquals("roms", first.source.path)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parsed = ManifestParser.parse(manifest(extra = ""","author":"me","future":{"a":1}"""))
        assertEquals("My addon", parsed.name)
    }

    @Test
    fun `a newer spec asks the user to update the app`() {
        assertEquals(AddonException.Kind.NewerSpec, failure(manifest(spec = "2")).kind)
    }

    @Test
    fun `spec must be a number`() {
        assertEquals(AddonException.Kind.Invalid, failure(manifest(spec = "\"1\"")).kind)
        assertEquals(AddonException.Kind.Invalid, failure("""{"id":"x"}""").kind)
    }

    @Test
    fun `not json and not an object are invalid`() {
        assertEquals(AddonException.Kind.Invalid, failure("<html>").kind)
        assertEquals(AddonException.Kind.Invalid, failure("[]").kind)
    }

    @Test
    fun `a bad addon id is rejected`() {
        assertEquals(AddonException.Kind.Invalid, failure(manifest(id = "\"Not Valid\"")).kind)
    }

    @Test
    fun `duplicate console ids are rejected across categories`() {
        val text = """
            {"spec":1,"id":"dup","name":"Dup","version":"1","categories":[
              {"id":"a","name":"A","consoles":[{"id":"c1","name":"C","collections":[]}]},
              {"id":"b","name":"B","consoles":[{"id":"c1","name":"C","collections":[]}]}]}
        """.trimIndent()
        assertTrue(failure(text).detail.orEmpty().contains("duplicate console id"))
    }

    @Test
    fun `paths that could leave the item are rejected`() {
        for (path in listOf("../x", "a/../b", "/abs", "a\\\\b", "a//b")) {
            val console = """{"id":"c","name":"C","collections":[{"title":"T","source":{"type":"archive.org-item","id":"i","path":"$path"}}]}"""
            val error = failure(manifest(consoles = console))
            assertTrue("$path: ${error.detail}", error.detail.orEmpty().contains("source.path"))
        }
    }

    @Test
    fun `a bad archive identifier is rejected`() {
        val console = """{"id":"c","name":"C","collections":[{"title":"T","source":{"type":"archive.org-item","id":"has space"}}]}"""
        assertTrue(failure(manifest(consoles = console)).detail.orEmpty().contains("source.id"))
    }

    @Test
    fun `collections with a source type from a newer spec are skipped`() {
        val console = """
            {"id":"c","name":"C","collections":[
              {"title":"Known","source":{"type":"archive.org-item","id":"item"}},
              {"title":"Future","source":{"type":"magic-source","id":"???"}}]}
        """.trimIndent()
        val parsed = ManifestParser.parse(manifest(consoles = console))
        assertEquals(listOf("Known"), parsed.categories.single().consoles.single().collections.map { it.title })
    }

    @Test
    fun `homepage must be https`() {
        assertEquals(AddonException.Kind.Invalid, failure(manifest(extra = ""","homepage":"http://example.com"""")).kind)
    }

    @Test
    fun `expand zip is accepted and other values are rejected`() {
        fun console(expand: String) =
            """{"id":"c","name":"C","collections":[{"title":"T","source":{"type":"archive.org-item","id":"i","expand":"$expand"}}]}"""
        val ok = ManifestParser.parse(manifest(consoles = console("zip")))
        assertEquals("zip", ok.categories.single().consoles.single().collections.single().source.expand)
        assertTrue(failure(manifest(consoles = console("rar"))).detail.orEmpty().contains("source.expand"))
    }

    @Test
    fun `https addresses are accepted and normalized`() {
        assertEquals("https://example.com/a.json", AddonUrl.parse("  https://example.com/a.json ", allowLocalHttp = false))
    }

    @Test
    fun `plain http is only accepted for local hosts in debug builds`() {
        assertEquals(AddonException.Kind.InsecureUrl, kindOf("http://example.com/a.json", allowLocal = false))
        assertEquals(AddonException.Kind.InsecureUrl, kindOf("http://10.0.2.2:8765/a.json", allowLocal = false))
        assertEquals(AddonException.Kind.InsecureUrl, kindOf("http://example.com/a.json", allowLocal = true))
        assertFalse(AddonUrl.parse("http://10.0.2.2:8765/a.json", allowLocalHttp = true).isEmpty())
        assertFalse(AddonUrl.parse("http://localhost:8765/a.json", allowLocalHttp = true).isEmpty())
    }

    @Test
    fun `nonsense and other schemes are invalid addresses`() {
        assertEquals(AddonException.Kind.InvalidUrl, kindOf("not a url", allowLocal = false))
        assertEquals(AddonException.Kind.InvalidUrl, kindOf("ftp://example.com/a.json", allowLocal = false))
        assertEquals(AddonException.Kind.InvalidUrl, kindOf("", allowLocal = false))
    }

    private fun kindOf(url: String, allowLocal: Boolean): AddonException.Kind =
        try {
            AddonUrl.parse(url, allowLocal)
            fail("expected the address to be rejected")
            error("unreachable")
        } catch (e: AddonException) {
            e.kind
        }
}
