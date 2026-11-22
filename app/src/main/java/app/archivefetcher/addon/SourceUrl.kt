package app.archivefetcher.addon

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides whether an address from an addon (a listing page, a file feed or a download) may be contacted.
 * Only https, and never the device itself or the local network, so an addon cannot make the app probe a router
 * or other machines at home. Debug builds also accept the local test hosts over http.
 */
object SourceUrl {
    private const val MAX_LENGTH = 2000
    private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun parse(text: String, allowLocalHttp: Boolean): HttpUrl? {
        if (text.length > MAX_LENGTH) return null
        val url = text.trim().toHttpUrlOrNull() ?: return null
        if (allowLocalHttp && url.host in localHosts) return url
        if (!url.isHttps || isPrivateHost(url.host)) return null
        return url
    }

    fun isAllowed(text: String, allowLocalHttp: Boolean): Boolean = parse(text, allowLocalHttp) != null

    internal fun isPrivateHost(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")) return true
        if (':' in name) return isPrivateIpv6(name)
        val parts = ipv4.matchEntire(name)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        val (a, b) = parts[0] to parts[1]
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        return bare == "::1" || bare == "::" || bare.startsWith("fc") || bare.startsWith("fd") || bare.startsWith("fe8") ||
            bare.startsWith("fe9") || bare.startsWith("fea") || bare.startsWith("feb") || bare.startsWith("::ffff:")
    }
}
