package app.archivefetcher.data

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the user's Internet Archive credentials to requests for archive.org hosts.
 *
 * It is installed as a network interceptor so it also runs on redirects: archive.org answers a download with
 * a redirect to a different data-node host, and OkHttp would otherwise drop the Authorization header there.
 * Credentials are never sent to other hosts or over plain http.
 */
class AuthInterceptor(
    private val authorization: () -> String?,
    private val cookie: () -> String?,
    private val isTrusted: (HttpUrl) -> Boolean = ::isArchiveHost,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isTrusted(request.url)) return chain.proceed(request)
        val builder = request.newBuilder()
        authorization()?.let { builder.header("Authorization", it) }
        cookie()?.let { builder.header("Cookie", it) }
        return chain.proceed(builder.build())
    }

    companion object {
        fun isArchiveHost(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "archive.org" || url.host.endsWith(".archive.org"))
    }
}
