package app.archivefetcher.data

import app.archivefetcher.BuildConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

data class UpdateInfo(val version: String, val url: String)

/**
 * Looks for a newer release of the app on GitHub, at most once a day. It only reads the public page of the latest
 * release; nothing about the user or the device is sent besides what any web request carries.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val prefs: Prefs,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The newer release to tell the user about, or null: none, turned off, or the user dismissed that version. */
    suspend fun check(): UpdateInfo? {
        if (!prefs.checkUpdates) return null
        if (clock() - prefs.lastUpdateCheck >= CHECK_EVERY_MS) {
            try {
                fetchLatest()?.let { (version, url) ->
                    prefs.latestVersion = version
                    prefs.latestUrl = url
                }
                prefs.lastUpdateCheck = clock()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline, rate limited or the page moved: try again at the next start.
            }
        }
        val latest = prefs.latestVersion
        val url = prefs.latestUrl
        if (latest.isEmpty() || url.isEmpty() || latest == prefs.dismissedVersion) return null
        return if (isNewer(latest, currentVersion)) UpdateInfo(latest, url) else null
    }

    fun dismiss(info: UpdateInfo) {
        prefs.dismissedVersion = info.version
    }

    private suspend fun fetchLatest(): Pair<String, String>? {
        val body = client.getStringLimited(LATEST_RELEASE, MAX_BYTES)
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = root["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(RELEASES_PAGE) } ?: return null
        return tag.removePrefix("v").trim() to url
    }

    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/DanieleKun/Cartshelf/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/DanieleKun/Cartshelf/releases/"
        private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
        private const val MAX_BYTES = 512L * 1024

        /** Compares dotted version numbers: "1.10.0" is newer than "1.9.2"; suffixes such as "-beta" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
