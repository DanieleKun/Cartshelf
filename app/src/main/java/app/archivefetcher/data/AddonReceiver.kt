package app.archivefetcher.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tiny web page on the local network, so an addon address or file can be sent from a phone instead of being typed
 * on a handheld. It runs only while the Addons screen asks for it, answers only on the Wi-Fi address and only to the
 * secret path in the address it shows, accepts at most [MAX_BODY] bytes, and never installs anything by itself: what
 * arrives goes to [start]'s callback, and the user confirms it on the device. It stops by itself after
 * [MAX_WRONG_PATHS] requests to a wrong path (someone guessing the address) or after [LIFETIME_MS].
 */
class AddonReceiver(
    private val scope: CoroutineScope,
    /** Where [start]'s callback runs: the main thread in the app, so it can update the screen. */
    private val deliver: CoroutineContext = Dispatchers.Main,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** The address to open on the phone, e.g. `http://192.168.1.20:41234/k7p2`; null when there is no local network. */
    fun start(page: ReceiverPage, onStopped: () -> Unit = {}, onReceived: suspend (String) -> Unit): String? {
        stop()
        val address = localAddress() ?: return null
        val socket = runCatching { ServerSocket(0, BACKLOG, address) }.getOrNull() ?: return null
        val token = newToken()
        server = socket
        val stopAt = System.currentTimeMillis() + LIFETIME_MS
        job = scope.launch(Dispatchers.IO) {
            var wrongPaths = 0
            try {
                socket.soTimeout = ACCEPT_POLL_MS
                while (isActive && !socket.isClosed && System.currentTimeMillis() < stopAt && wrongPaths < MAX_WRONG_PATHS) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        if (!client.use { handle(it, token, page, onReceived) }) wrongPaths++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A broken, slow or malformed request only ends that connection.
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
            // Stopped by itself (time or guesses), not by [stop]: the screen is told so it hides the address.
            if (isActive) withContext(deliver) { onStopped() }
        }
        return "http://${address.hostAddress}:${socket.localPort}/$token"
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    /** False when the request asked for a wrong path. */
    private suspend fun handle(socket: Socket, token: String, page: ReceiverPage, onReceived: suspend (String) -> Unit): Boolean {
        socket.soTimeout = TIMEOUT_MS
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input, deadline) ?: return true
        val parts = requestLine.split(' ')
        if (parts.size < 2) return true
        var length = 0L
        var headers = 0
        while (true) {
            val header = readLine(input, deadline) ?: return true
            if (header.isEmpty()) break
            if (++headers > MAX_HEADERS) return true
            if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toLongOrNull() ?: 0
        }
        val output = socket.getOutputStream()
        fun respond(status: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            output.write(body)
            output.flush()
        }
        if (parts[1] != "/$token") {
            respond("404 Not Found", page.html(sent = false, notFound = true))
            return false
        }
        when (parts[0]) {
            "GET" -> respond("200 OK", page.html(sent = false))
            "POST" -> {
                if (length <= 0 || length > MAX_BODY) {
                    respond("413 Payload Too Large", page.html(sent = false))
                    return true
                }
                val text = readBody(input, length.toInt(), deadline).toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) withContext(deliver) { onReceived(text) }
                respond("200 OK", page.html(sent = true))
            }
            else -> respond("405 Method Not Allowed", page.html(sent = false))
        }
        return true
    }

    /** A client sending a byte now and then would otherwise hold the receiver forever. */
    private fun checkDeadline(deadline: Long) {
        if (System.currentTimeMillis() > deadline) throw IOException("request too slow")
    }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_LINE) {
            checkDeadline(deadline)
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return bytes.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            bytes.write(b)
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            checkDeadline(deadline)
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return (1..TOKEN_LENGTH).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
    }

    /**
     * The device's address on the Wi-Fi or Ethernet network. Mobile data and VPN interfaces often carry private
     * addresses too, but those networks are not the user's own, so they are never used.
     */
    private fun localAddress(): InetAddress? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && HOME_INTERFACES.any(it.name::startsWith) }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

    private companion object {
        const val BACKLOG = 4
        const val TIMEOUT_MS = 5_000
        const val REQUEST_DEADLINE_MS = 20_000L
        const val ACCEPT_POLL_MS = 1_000
        const val LIFETIME_MS = 15L * 60 * 1000
        const val MAX_WRONG_PATHS = 20
        const val MAX_HEADERS = 50
        const val MAX_LINE = 8 * 1024
        const val MAX_BODY = 5L * 1024 * 1024
        const val TOKEN_LENGTH = 6

        /** Wi-Fi (also as a hotspot) and Ethernet. */
        val HOME_INTERFACES = listOf("wlan", "swlan", "ap", "eth")
        const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}

/** The texts of the phone page, in the app's language. */
data class ReceiverPage(
    val title: String,
    val intro: String,
    val urlLabel: String,
    val send: String,
    val fileLabel: String,
    val sent: String,
    val notFound: String,
) {
    fun html(sent: Boolean, notFound: Boolean = false): String {
        val body = when {
            notFound -> "<p>${esc(this.notFound)}</p>"
            sent -> "<p class=ok>${esc(this.sent)}</p>"
            else -> """
                <p>${esc(intro)}</p>
                <label>${esc(urlLabel)}<textarea id=t rows=3 placeholder="https://"></textarea></label>
                <button id=b>${esc(send)}</button>
                <label class=file>${esc(fileLabel)}<input id=f type=file accept=".json,application/json"></label>
                <script>
                  function go(text){fetch(location.pathname,{method:'POST',body:text}).then(r=>r.text()).then(h=>{document.open();document.write(h);document.close()})}
                  document.getElementById('b').onclick=function(){var v=document.getElementById('t').value.trim();if(v)go(v)}
                  document.getElementById('f').onchange=function(e){var file=e.target.files[0];if(!file)return;var r=new FileReader();r.onload=function(){go(r.result)};r.readAsText(file)}
                </script>
            """.trimIndent()
        }
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
            <title>${esc(title)}</title><style>
            body{font-family:system-ui,sans-serif;background:#0C0E12;color:#EFF2F6;margin:0;padding:24px;max-width:520px}
            h1{font-size:22px}p{color:#9DA7B5;line-height:1.5}label{display:block;margin:18px 0 6px;color:#9DA7B5}
            textarea{width:100%;box-sizing:border-box;margin-top:6px;background:#1E232C;color:#EFF2F6;border:1px solid #262C37;border-radius:12px;padding:12px;font-size:16px}
            button{margin-top:12px;width:100%;padding:14px;border:0;border-radius:12px;background:#FF8A3D;color:#241005;font-weight:600;font-size:16px}
            .file input{margin-top:8px;color:#EFF2F6}.ok{color:#4FD18B;font-size:18px}
            </style></head><body><h1>${esc(title)}</h1>$body</body></html>"""
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
