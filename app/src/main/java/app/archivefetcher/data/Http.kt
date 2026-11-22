package app.archivefetcher.data

import java.io.IOException
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

const val USER_AGENT = "ArchiveFetcher/0.1"

fun buildHttpClient(authorization: () -> String? = { null }, cookie: () -> String? = { null }): OkHttpClient =
    OkHttpClient.Builder()
        // A network interceptor, so the credentials are also added after archive.org redirects to a data node.
        .addNetworkInterceptor(AuthInterceptor(authorization, cookie))
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
