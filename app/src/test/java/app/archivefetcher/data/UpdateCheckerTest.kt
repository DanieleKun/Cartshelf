package app.archivefetcher.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private class MemoryStore : UpdateStore {
        override var checkUpdates = true
        override var lastUpdateCheck = 0L
        override var latestVersion = ""
        override var latestUrl = ""
        override var latestNotes = ""
        override var latestEtag = ""
        override var dismissedVersion = ""
    }

    private lateinit var server: MockWebServer
    private val store = MemoryStore()
    private var now = 1_000_000_000L

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun checker(current: String = "1.3.0") = UpdateChecker(
        OkHttpClient(), store, current, clock = { now },
        latestRelease = server.url("/latest").toString(),
        releasesPage = "https://github.com/DanieleKun/Cartshelf/releases/",
    )

    private fun release(version: String, etag: String = "\"e-$version\"", body: String = "- Fixed the folder picker.") =
        MockResponse().setHeader("ETag", etag).setBody(
            """{"tag_name":"v$version","html_url":"https://github.com/DanieleKun/Cartshelf/releases/tag/v$version","body":${quote(body)}}""",
        )

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    @Test
    fun `a newer release is announced with its notes`() = runBlocking {
        server.enqueue(release("1.4.0"))
        val info = checker().check()
        assertEquals(UpdateInfo("1.4.0", "https://github.com/DanieleKun/Cartshelf/releases/tag/v1.4.0", "• Fixed the folder picker."), info)
        assertEquals("\"e-1.4.0\"", store.latestEtag)
    }

    @Test
    fun `no second request within the interval`() = runBlocking {
        server.enqueue(release("1.4.0"))
        checker().check()
        now += UpdateChecker.MIN_INTERVAL_MS - 1
        assertEquals("1.4.0", checker().check()?.version)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `later checks ask whether anything changed and keep the answer they had`() = runBlocking {
        server.enqueue(release("1.4.0"))
        checker().check()
        now += UpdateChecker.MIN_INTERVAL_MS
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals("1.4.0", checker().check()?.version)
        server.takeRequest()
        assertEquals("\"e-1.4.0\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a release published after the last check shows at the next opening`() = runBlocking {
        server.enqueue(release("1.3.0"))
        assertNull(checker().check())
        now += UpdateChecker.MIN_INTERVAL_MS
        server.enqueue(release("1.4.0"))
        assertEquals("1.4.0", checker().check()?.version)
    }

    @Test
    fun `check now ignores the interval, the switch and a dismissed version`() = runBlocking {
        server.enqueue(release("1.4.0"))
        val first = checker().check()!!
        checker().dismiss(first)
        store.checkUpdates = false
        assertNull(checker().check())
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(UpdateCheck.Available(first), checker().checkNow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `check now says when the app is up to date or when GitHub cannot be reached`() = runBlocking {
        server.enqueue(release("1.3.0"))
        assertEquals(UpdateCheck.UpToDate, checker().checkNow())
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateCheck.Failed, checker().checkNow())
    }

    @Test
    fun `a release page outside the project is ignored`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"tag_name":"v9.0.0","html_url":"https://example.com/evil","body":""}"""),
        )
        assertEquals(UpdateCheck.Failed, checker().checkNow())
        assertEquals("", store.latestVersion)
    }

    @Test
    fun `release notes become short plain text without the list GitHub adds`() {
        val markdown = """
            ## Fixes
            - Choosing a **folder** works again, see [the issue](https://example.com/1).
            * Uses `less` data.

            ## What's Changed
            * something by someone

            **Full Changelog**: https://github.com/x/y/compare/v1...v2
        """.trimIndent()
        assertEquals("Fixes\n• Choosing a folder works again, see the issue.\n• Uses less data.", UpdateChecker.releaseNotes(markdown))
        assertEquals("", UpdateChecker.releaseNotes("**Full Changelog**: https://github.com/x/y/compare/v1...v2"))
        assertEquals(8, UpdateChecker.releaseNotes((1..20).joinToString("\n") { "- line $it" }).lines().size)
    }
}
