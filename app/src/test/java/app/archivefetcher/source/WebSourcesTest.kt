package app.archivefetcher.source

import app.archivefetcher.addon.AddonException
import app.archivefetcher.addon.CatalogMerger
import app.archivefetcher.addon.ManifestParser
import app.archivefetcher.addon.SourceUrl
import app.archivefetcher.data.CatalogEntry
import app.archivefetcher.data.IndexStore
import app.archivefetcher.data.LaterItem
import app.archivefetcher.data.RemoteFile
import app.archivefetcher.data.RomFile
import app.archivefetcher.data.SourceTypes
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSourcesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun manifest(source: String) = """
        {"spec":1,"id":"web-addon","name":"Web","version":"1",
         "categories":[{"id":"nintendo","name":"Nintendo","consoles":[{"id":"nintendo-gb","name":"Nintendo - Game Boy",
         "collections":[{"title":"Homebrew","source":$source}]}]}]}
    """.trimIndent()

    private fun invalid(source: String) {
        try {
            ManifestParser.parse(manifest(source))
            fail("expected an invalid manifest: $source")
        } catch (e: AddonException) {
            assertEquals(AddonException.Kind.Invalid, e.kind)
        }
    }

    @Test
    fun `the three web source types are accepted`() {
        val dir = ManifestParser.parse(manifest("""{"type":"http-directory","url":"https://example.org/gb/","extensions":["gb","zip"]}"""))
        val list = ManifestParser.parse(manifest("""{"type":"file-list","url":"https://example.org/gb.json"}"""))
        val files = ManifestParser.parse(
            manifest(
                """{"type":"files","files":[{"name":"Tobu Tobu Girl (World).gb","url":"https://example.org/t.gb",""" +
                    """"size":65536,"md5":"0123456789abcdef0123456789ABCDEF"}]}""",
            ),
        )
        listOf(dir, list, files).forEach { assertEquals(1, it.collectionCount) }
        val entries = CatalogMerger.merge(listOf(dir, list, files)).console("nintendo-gb")!!.entries
        assertEquals(listOf(SourceTypes.HTTP_DIRECTORY, SourceTypes.FILE_LIST, SourceTypes.FILES), entries.map { it.sourceType })
        assertEquals(listOf("gb", "zip"), entries[0].extensions)
        assertEquals("https://example.org/t.gb", entries[2].files.single().url)
    }

    @Test
    fun `web sources must be public https addresses with safe names`() {
        invalid("""{"type":"file-list","url":"http://example.org/gb.json"}""")
        invalid("""{"type":"file-list","url":"https://192.168.1.1/gb.json"}""")
        invalid("""{"type":"file-list","url":"https://nas.local/gb.json"}""")
        invalid("""{"type":"http-directory","url":"https://example.org/gb/"}""")
        invalid("""{"type":"http-directory","url":"https://example.org/gb/","extensions":["GB!"]}""")
        invalid("""{"type":"files","files":[{"name":"../evil.gb","url":"https://example.org/e.gb"}]}""")
        invalid("""{"type":"files","files":[{"name":"a.gb","url":"https://example.org/a.gb","md5":"nope"}]}""")
        invalid("""{"type":"files","files":[{"name":"a.gb","url":"https://example.org/a.gb"},{"name":"a.gb","url":"https://example.org/b.gb"}]}""")
        invalid("""{"type":"file-list","url":"https://example.org/gb.json","expand":"zip"}""")
    }

    @Test
    fun `unknown source types are still skipped`() {
        assertEquals(0, ManifestParser.parse(manifest("""{"type":"future-type","url":"https://example.org/x"}""")).collectionCount)
    }

    @Test
    fun `private and local hosts are refused`() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "172.20.0.1", "192.168.0.10", "169.254.1.1", "router.local", "::1", "fd00::1")
            .forEach { assertTrue(it, SourceUrl.isPrivateHost(it)) }
        assertFalse(SourceUrl.isPrivateHost("archive.org"))
        assertFalse(SourceUrl.isPrivateHost("8.8.8.8"))
        assertTrue(SourceUrl.isAllowed("http://10.0.2.2:8000/a.json", allowLocalHttp = true))
        assertFalse(SourceUrl.isAllowed("http://10.0.2.2:8000/a.json", allowLocalHttp = false))
    }

    @Test
    fun `a directory page yields the files with the wanted extensions on the same host`() {
        val html = """
            <a href="?C=N;O=D">Name</a> <a href="../">Parent</a> <a href="sub/">sub/</a>
            <a href="Tobu%20Tobu%20Girl%20(World).gb">Tobu</a>
            <a href="/gb/Deadeus%20(Italy).zip">Deadeus</a>
            <a href="readme.txt">readme</a>
            <a href="https://other.example/x.gb">elsewhere</a>
            <a href="Tobu%20Tobu%20Girl%20(World).gb">duplicate</a>
        """
        val files = RemoteFiles.parseDirectory(html, "https://example.org/gb/", listOf("gb", "zip"), allowLocalHttp = false)
        assertEquals(listOf("Tobu Tobu Girl (World).gb", "Deadeus (Italy).zip"), files.map { it.name })
        assertEquals("https://example.org/gb/Deadeus%20(Italy).zip", files[1].url)
    }

    @Test
    fun `a file list keeps valid entries and skips the others`() {
        val json = """{"files":[
            {"name":"Good (Europe).gb","url":"https://example.org/good.gb","size":1024,"md5":"0123456789ABCDEF0123456789abcdef"},
            {"name":"insecure.gb","url":"http://example.org/insecure.gb"},
            {"name":"../escape.gb","url":"https://example.org/escape.gb"},
            {"name":"Good (Europe).gb","url":"https://example.org/again.gb"}
        ],"extra":true}"""
        val files = RemoteFiles.parseFileList(json, allowLocalHttp = false)
        assertEquals(listOf(RemoteFile("Good (Europe).gb", "https://example.org/good.gb", 1024, "0123456789abcdef0123456789abcdef")), files)
    }

    @Test
    fun `web sources list files with their own download address`() = runTest {
        val server = MockWebServer()
        server.start()
        // Only the local test hosts may use http, so address the server as "localhost" rather than the machine name.
        fun url(path: String) = server.url(path).newBuilder().host("localhost").build().toString()
        try {
            server.enqueue(MockResponse().setBody("""<a href="Pokemon%20-%20Versione%20Argento%20(Italy).gbc">x</a>"""))
            server.enqueue(MockResponse().setBody("""{"files":[{"name":"Homebrew (World).gb","url":"${url("/h.gb")}","size":7}]}"""))
            val sources = WebSources(OkHttpClient(), allowLocalHttp = true)
            val page = url("/gbc/")
            val dir = sources.fetch(CatalogEntry("Dir", page, sourceType = SourceTypes.HTTP_DIRECTORY, extensions = listOf("gbc")), cache = false)
            val file = dir.files.single()
            assertEquals("Pokemon - Versione Argento (Italy).gbc", file.displayName)
            assertEquals(listOf("It"), file.languages)
            assertTrue(file.url!!.endsWith("/gbc/Pokemon%20-%20Versione%20Argento%20(Italy).gbc"))

            val list = sources.fetch(CatalogEntry("List", url("/list.json"), sourceType = SourceTypes.FILE_LIST), cache = false)
            assertEquals(7L, list.files.single().size)
            assertEquals("gb", list.files.single().format)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `malformed feed entries are skipped and addresses are normalized`() {
        val json = """{"files":[{"name":42},{"url":"https://example.org/x.gb"},{"name":"Ok.gb","url":" https://EXAMPLE.org/ok.gb "}]}"""
        assertEquals(listOf(RemoteFile("Ok.gb", "https://example.org/ok.gb")), RemoteFiles.parseFileList(json, allowLocalHttp = false))
    }

    @Test
    fun `same name in two folders of a page is listed once`() {
        val html = """<a href="a/x.zip">1</a><a href="b/x.zip">2</a><a href="https://example.org:8443/gb/y.zip">port</a>"""
        val files = RemoteFiles.parseDirectory(html, "https://example.org/gb/", listOf("zip"), allowLocalHttp = false)
        assertEquals(listOf("x.zip"), files.map { it.name })
    }

    @Test
    fun `connections to local addresses are refused, redirects included`() {
        val socket = app.archivefetcher.data.PublicSocketFactory().createSocket()
        try {
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 9), 500)
            fail("expected the local connection to be refused")
        } catch (e: java.net.ConnectException) {
            assertTrue(e.message!!.contains("local address"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun `the direct address survives the search index and the later list`() {
        val file = RomFile.create("https://example.org/gb/", "Game (USA).gb", 10, "gb", "Dir", false, null, "https://example.org/gb/Game.gb")
        val store = IndexStore(folder.newFolder("index"))
        store.write("nintendo-gb", listOf(file))
        assertEquals("https://example.org/gb/Game.gb", store.search("game", 10, listOf("nintendo-gb")).hits.single().file.url)
        assertEquals("https://example.org/gb/Game.gb", LaterItem.of("nintendo-gb", "Game Boy", file).toRomFile().url)
        assertNull(LaterItem.of("x", "x", RomFile.create("item", "a.zip", 1, "zip", "p", false, null)).url)
    }
}
