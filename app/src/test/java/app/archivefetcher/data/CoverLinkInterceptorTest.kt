package app.archivefetcher.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CoverLinkInterceptorTest {
    private lateinit var server: ServerSocket
    private lateinit var client: OkHttpClient
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /** name → (content type, body); everything else is a 404. */
    private val files = mapOf(
        "Real (USA).png" to ("image/png" to png),
        "Link (Europe).png" to ("text/plain" to "Real (USA).png".toByteArray()),
        "Short (Japan).png" to ("image/png" to png),
    )

    @Before
    fun start() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { answer(it) }
            }
        }
        client = OkHttpClient.Builder().addInterceptor(CoverLinkInterceptor(host = "127.0.0.1")).build()
    }

    /** One request per connection: reads the request line and headers, answers with the file or a 404. */
    private fun answer(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (input.readLine()?.isNotEmpty() == true) Unit
        val file = files[URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")]
        val out = socket.getOutputStream()
        if (file == null) {
            out.write("HTTP/1.1 404 Not Found${CRLF}Content-Type: text/plain${CRLF}Content-Length: 0${CRLF}Connection: close$CRLF$CRLF".toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK${CRLF}Content-Type: ${file.first}${CRLF}Content-Length: ${file.second.size}${CRLF}Connection: close$CRLF$CRLF".toByteArray())
            out.write(file.second)
        }
        out.flush()
    }

    @After
    fun stop() = server.close()

    private companion object {
        val CRLF = String(charArrayOf('\r', '\n'))
    }

    private fun get(name: String): Pair<Int, String?> {
        val url = "http://127.0.0.1:${server.localPort}/repo/master/Named_Boxarts/${name.replace(" ", "%20")}"
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body?.contentType()?.type }
    }

    @Test
    fun `a missing picture without other names is a plain 404`() {
        assertEquals(404 to null, get("Nothing.png").let { it.first to null })
    }

    @Test
    fun `a missing picture is found under its shorter name`() {
        assertEquals(200 to "image", get("Short (Japan) (En).png"))
    }

    @Test
    fun `a link file leads to the real picture`() {
        assertEquals(200 to "image", get("Link (Europe).png"))
    }

    @Test
    fun `nothing found after the other names is still a 404`() {
        assertEquals(404, get("Missing (USA) (En) (Rev 1).png").first)
    }
}
