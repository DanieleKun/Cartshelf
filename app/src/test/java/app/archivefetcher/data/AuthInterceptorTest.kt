package app.archivefetcher.data

import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var target: MockWebServer
    private lateinit var redirector: MockWebServer

    @Before
    fun setUp() {
        target = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200))
            start()
        }
        // Redirects to the same server through a different host name, like archive.org does to its data nodes.
        redirector = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${target.port}/file"))
            start()
        }
    }

    @After
    fun tearDown() {
        target.shutdown()
        redirector.shutdown()
    }

    /** Follows the redirect and returns what the final server received. */
    private fun fetch(trusted: (HttpUrl) -> Boolean): RecordedRequest {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(AuthInterceptor({ "LOW key:secret" }, { "logged-in-user=me" }, trusted))
            .build()
        client.newCall(Request.Builder().url(redirector.url("/start")).build()).execute().use {
            assertEquals(200, it.code)
        }
        return requireNotNull(target.takeRequest(5, TimeUnit.SECONDS))
    }

    @Test
    fun `credentials survive a redirect to another trusted host`() {
        val received = fetch { true }
        assertEquals("LOW key:secret", received.getHeader("Authorization"))
        assertEquals("logged-in-user=me", received.getHeader("Cookie"))
    }

    @Test
    fun `credentials are not sent to hosts that are not trusted`() {
        val received = fetch { it.host != "127.0.0.1" }
        assertNull(received.getHeader("Authorization"))
        assertNull(received.getHeader("Cookie"))
    }

    @Test
    fun `only https archive org hosts are trusted by default`() {
        assertTrue(AuthInterceptor.isArchiveHost("https://archive.org/download/x".toHttpUrl()))
        assertTrue(AuthInterceptor.isArchiveHost("https://dn720205.ca.archive.org/0/items/x".toHttpUrl()))
        assertFalse(AuthInterceptor.isArchiveHost("http://archive.org/download/x".toHttpUrl()))
        assertFalse(AuthInterceptor.isArchiveHost("https://evilarchive.org/x".toHttpUrl()))
        assertFalse(AuthInterceptor.isArchiveHost("https://archive.org.evil.com/x".toHttpUrl()))
    }
}
