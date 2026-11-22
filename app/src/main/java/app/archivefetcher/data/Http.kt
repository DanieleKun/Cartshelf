package app.archivefetcher.data

import java.io.IOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory
import okhttp3.Dispatcher
import okhttp3.Dns
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

const val USER_AGENT = "Cartshelf/1.0"

/**
 * Resolves names only to public addresses. Addons can point at any https host, so without this a name (or a redirect)
 * that resolves to the phone itself or to the home network would let an addon reach a router or a NAS.
 * Debug builds still reach the local test hosts.
 */
class PublicDns(private val allowLocalTestHosts: Boolean) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (allowLocalTestHosts && hostname in LOCAL_TEST_HOSTS) return all
        val public = all.filterNot(::isLocal)
        if (public.isEmpty()) throw UnknownHostException("$hostname resolves only to local addresses")
        return public
    }

    companion object {
        private val LOCAL_TEST_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        fun isLocal(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress ||
                (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
}

/**
 * Refuses to open a connection to a local address. [PublicDns] covers names, but a redirect to an IP literal
 * (`https://192.168.1.1/`) never goes through DNS; checking at connect time covers every hop of every request.
 */
class PublicSocketFactory : SocketFactory() {
    private val system = getDefault()

    private class PublicSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address != null && PublicDns.isLocal(address)) throw ConnectException("connection to a local address refused")
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(): Socket = PublicSocket()

    // OkHttp only uses the unconnected form above; the others are refused rather than left unchecked.
    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException()
}

private const val MAX_REQUESTS_PER_HOST = 12

fun buildHttpClient(
    authorization: () -> String? = { null },
    cookie: () -> String? = { null },
    allowLocalTestHosts: Boolean = false,
): OkHttpClient =
    OkHttpClient.Builder()
        // The default of 5 calls per host let the background index (up to 6 listings) starve the screen being browsed.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
        // A network interceptor, so the credentials are also added after archive.org redirects to a data node.
        .addNetworkInterceptor(AuthInterceptor(authorization, cookie))
        .dns(PublicDns(allowLocalTestHosts))
        .apply { if (!allowLocalTestHosts) socketFactory(PublicSocketFactory()) }
        .apply {
            // Same validation as the platform, plus a retry without the stray old root some servers send.
            runCatching { ChainTrimmingTrustManager.create() }.getOrNull()?.let { (factory, manager) -> sslSocketFactory(factory, manager) }
        }
        .followSslRedirects(false)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

class HttpStatusException(val code: Int, message: String) : IOException(message)

class ResponseTooLargeException(val limit: Long) : IOException("response larger than $limit bytes")

/** Like [getString] but gives up as soon as the body exceeds [maxBytes]. */
suspend fun OkHttpClient.getStringLimited(url: String, maxBytes: Long): String {
    newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code}")
        val body = response.body ?: return ""
        if (body.contentLength() > maxBytes) throw ResponseTooLargeException(maxBytes)
        return withContext(Dispatchers.IO) {
            val buffer = Buffer()
            val source = body.source()
            while (buffer.size <= maxBytes) {
                if (source.read(buffer, READ_CHUNK) == -1L) break
            }
            if (buffer.size > maxBytes) throw ResponseTooLargeException(maxBytes)
            buffer.readUtf8()
        }
    }
}

private const val READ_CHUNK = 8192L

suspend fun OkHttpClient.getString(url: String): String {
    newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code}")
        return withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
    }
}
