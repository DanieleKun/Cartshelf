package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLoginTest {
    @Test
    fun `a refused login reports the reason code`() {
        // Shape returned by archive.org for an unknown account.
        val result = LoginParser.parse("""{"success":false,"values":{"reason":"account_not_found"},"version":1}""")
        assertEquals(LoginResult.Failure("account_not_found"), result)
    }

    @Test
    fun `a successful login yields the session cookies`() {
        val body = """{"success":true,"values":{"cookies":{"logged-in-user":"me%40example.com","logged-in-sig":"abc"}}}"""
        val result = LoginParser.parse(body) as LoginResult.Success
        assertEquals(mapOf("logged-in-user" to "me%40example.com", "logged-in-sig" to "abc"), result.cookies)
    }

    @Test
    fun `a success without cookies is treated as a failure`() {
        assertTrue(LoginParser.parse("""{"success":true,"values":{}}""") is LoginResult.Failure)
    }

    @Test
    fun `garbage is a failure not a crash`() {
        assertTrue(LoginParser.parse("<html>nope</html>") is LoginResult.Failure)
    }
}
