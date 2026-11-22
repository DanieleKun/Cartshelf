package app.archivefetcher.addon

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var now = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(): AddonRepository =
        AddonRepository(AddonStore(File(folder.root, "addons")), OkHttpClient(), allowLocalHttp = true, clock = { now })

    private fun manifest(id: String = "demo", consoleId: String = "sony-psp", title: String = "First") = """
        {"spec":1,"id":"$id","name":"Demo $id","version":"1","categories":[
          {"id":"sony","name":"Sony","consoles":[{"id":"$consoleId","name":"PSP","collections":[
            {"title":"$title","source":{"type":"archive.org-item","id":"item"}}]}]}]}
    """.trimIndent()

    private fun serve(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    // MockWebServer reports the machine name as host; the app only accepts "localhost" for plain http.
    private fun url(path: String = "/demo.addon.json") = server.url(path).newBuilder().host("localhost").build().toString()

    private fun kindOf(block: suspend () -> Unit): AddonException.Kind =
        try {
            runBlocking { block() }
            fail("expected an AddonException")
            error("unreachable")
        } catch (e: AddonException) {
            e.kind
        }

    @Test
    fun `installing an addon adds its consoles to the catalog`() = runBlocking {
        val repo = repository()
        serve(manifest())

        val outcome = repo.install(url())

        assertFalse(outcome.replaced)
        assertEquals(listOf("demo"), repo.addons.value.map { it.id })
        assertEquals("sony-psp", repo.catalog.value.console("sony-psp")?.info?.id)
    }

    @Test
    fun `an installed addon survives a restart`() = runBlocking {
        serve(manifest())
        repository().install(url())

        val reopened = repository().apply { load() }
        assertEquals(listOf("demo"), reopened.addons.value.map { it.id })
        assertFalse(reopened.catalog.value.isEmpty)
    }

    @Test
    fun `installing the same id again replaces the addon`() = runBlocking {
        val repo = repository()
        serve(manifest(title = "Old"))
        repo.install(url())
        serve(manifest(title = "New"))

        val outcome = repo.install(url())

        assertTrue(outcome.replaced)
        assertEquals(1, repo.addons.value.size)
        assertEquals("New", repo.catalog.value.console("sony-psp")!!.entries.single().title)
    }

    @Test
    fun `an invalid manifest is rejected and nothing is stored`() {
        val repo = repository()
        serve("""{"spec":1,"id":"BAD ID","name":"x","version":"1","categories":[]}""")
        assertEquals(AddonException.Kind.Invalid, kindOf { repo.install(url()) })
        assertTrue(repo.addons.value.isEmpty())
    }

    @Test
    fun `a manifest from a newer spec is refused with a clear reason`() {
        serve("""{"spec":9,"id":"future","name":"x","version":"1"}""")
        assertEquals(AddonException.Kind.NewerSpec, kindOf { repository().install(url()) })
    }

    @Test
    fun `an oversized manifest is refused`() {
        serve(" ".repeat((AddonSpec.MAX_BYTES + 1).toInt()))
        assertEquals(AddonException.Kind.TooLarge, kindOf { repository().install(url()) })
    }

    @Test
    fun `http errors and unreachable hosts are reported as network problems`() {
        serve("", code = 404)
        assertEquals(AddonException.Kind.Network, kindOf { repository().install(url()) })

        val dead = url()
        server.shutdown()
        assertEquals(AddonException.Kind.Network, kindOf { repository().install(dead) })
    }

    @Test
    fun `an insecure address is refused before any request is made`() {
        assertEquals(AddonException.Kind.InsecureUrl, kindOf { repository().install("http://example.com/a.json") })
    }

    @Test
    fun `refresh updates the stored copy`() = runBlocking {
        val repo = repository()
        serve(manifest(title = "Old"))
        repo.install(url())
        serve(manifest(title = "New"))
        now = 5_000L

        val refreshed = repo.refresh("demo")

        assertEquals(5_000L, refreshed.fetchedAt)
        assertEquals("New", repo.catalog.value.console("sony-psp")!!.entries.single().title)
    }

    @Test
    fun `refresh refuses a manifest whose id changed`() {
        val repo = repository()
        serve(manifest())
        runBlocking { repo.install(url()) }
        serve(manifest(id = "someone-else"))
        assertEquals(AddonException.Kind.Invalid, kindOf { repo.refresh("demo") })
        assertEquals(listOf("demo"), repo.addons.value.map { it.id })
    }

    @Test
    fun `refreshing stale addons keeps the stored copy when the host is down`() = runBlocking {
        val repo = repository()
        serve(manifest(title = "Kept"))
        repo.install(url())
        now += 24 * 60 * 60 * 1000L
        serve("", code = 503)

        repo.refreshStale(maxAgeMillis = 60_000L)

        assertEquals("Kept", repo.catalog.value.console("sony-psp")!!.entries.single().title)
    }

    @Test
    fun `fresh addons are not requested again`() = runBlocking {
        val repo = repository()
        serve(manifest())
        repo.install(url())
        val requestsBefore = server.requestCount

        repo.refreshStale(maxAgeMillis = 60_000L)

        assertEquals(requestsBefore, server.requestCount)
    }

    @Test
    fun `removing an addon removes its consoles`() = runBlocking {
        val repo = repository()
        serve(manifest())
        repo.install(url())

        repo.remove("demo")

        assertTrue(repo.addons.value.isEmpty())
        assertTrue(repo.catalog.value.isEmpty)
        assertTrue(repository().apply { load() }.addons.value.isEmpty())
    }
}
