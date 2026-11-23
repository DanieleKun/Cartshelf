package app.archivefetcher.data

import app.archivefetcher.BuildConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(val version: String, val url: String, val notes: String = "")

/** The answer to "check now" in the settings. */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data object Failed : UpdateCheck
}

/** What the checker remembers between starts. */
interface UpdateStore {
    var checkUpdates: Boolean
    var lastUpdateCheck: Long
    var latestVersion: String
    var latestUrl: String
    var latestNotes: String
    var latestEtag: String
    var dismissedVersion: String
}

/**
 * Looks for a newer release of the app on GitHub when the app opens, at most every [MIN_INTERVAL_MS]. It only reads
 * the public page of the latest release; nothing about the user or the device is sent besides what any web request
 * carries. Repeated checks ask GitHub "anything new since this answer?", so when there is not the answer is a few
 * bytes. Every check still counts towards GitHub's limit of 60 requests an hour per address, far above one in ten minutes.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val store: UpdateStore,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
    private val latestRelease: String = LATEST_RELEASE,
    private val releasesPage: String = RELEASES_PAGE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The newer release to tell the user about, or null: none, turned off, or the user dismissed that version. */
    suspend fun check(): UpdateInfo? {
        if (!store.checkUpdates) return null
        if (clock() - store.lastUpdateCheck >= MIN_INTERVAL_MS) refresh()
        return pending()?.takeIf { it.version != store.dismissedVersion }
    }

    /** Asked for by the user: always asks GitHub, works with the notice turned off and shows a dismissed version too. */
    suspend fun checkNow(): UpdateCheck {
        if (!refresh()) return UpdateCheck.Failed
        return pending()?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
    }

    fun dismiss(info: UpdateInfo) {
        store.dismissedVersion = info.version
    }

    private fun pending(): UpdateInfo? {
        val latest = store.latestVersion
        val url = store.latestUrl
        if (latest.isEmpty() || url.isEmpty() || !isNewer(latest, currentVersion)) return null
        return UpdateInfo(latest, url, store.latestNotes)
    }

    /** True when GitHub answered, with news or with "nothing new". */
    private suspend fun refresh(): Boolean = try {
        fetchLatest()
        store.lastUpdateCheck = clock()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Offline, rate limited or the page moved: try again at the next start.
        false
    }

    private suspend fun fetchLatest() {
        val request = Request.Builder().url(latestRelease).header("Accept", "application/vnd.github+json")
        // The saved tag is only worth sending while the answer it belongs to is still saved.
        if (store.latestVersion.isNotEmpty() && store.latestEtag.isNotEmpty()) request.header("If-None-Match", store.latestEtag)
        client.newCall(request.build()).awaitResponse().use { response ->
            if (response.code == HTTP_NOT_MODIFIED) return
            if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code}")
            val root = json.parseToJsonElement(response.readStringLimited(MAX_BYTES)).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no tag")
            val url = root["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(releasesPage) }
                ?: throw IllegalStateException("unexpected release page")
            store.latestVersion = tag.removePrefix("v").trim()
            store.latestUrl = url
            store.latestNotes = releaseNotes(root["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
            store.latestEtag = response.header("ETag").orEmpty()
        }
    }

    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/DanieleKun/Cartshelf/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/DanieleKun/Cartshelf/releases/"
        const val MIN_INTERVAL_MS = 10L * 60 * 1000
        private const val MAX_BYTES = 512L * 1024
        private const val HTTP_NOT_MODIFIED = 304
        private const val MAX_NOTE_LINES = 8
        private const val MAX_NOTES_LENGTH = 600

        private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val emphasis = Regex("""\*\*|__|`""")
        private val bullet = Regex("""^[-*+]\s+""")
        private val heading = Regex("""^#+\s*""")

        /**
         * The notes of a release as short plain text for the notice: Markdown marks removed, the list GitHub adds
         * by itself ("What's Changed", "Full Changelog") left out, and cut to a few lines.
         */
        fun releaseNotes(markdown: String): String {
            val lines = markdown.lineSequence()
                .map { it.trim() }
                .takeWhile { !it.startsWith("## What's Changed") && !it.startsWith("**Full Changelog**") }
                .map { line ->
                    val plain = emphasis.replace(link.replace(line) { it.groupValues[1] }, "")
                    if (bullet.containsMatchIn(plain)) "• " + bullet.replace(plain, "") else heading.replace(plain, "")
                }
                .filter { it.isNotBlank() }
                .take(MAX_NOTE_LINES)
                .toList()
            val text = lines.joinToString("\n")
            return if (text.length <= MAX_NOTES_LENGTH) text else text.take(MAX_NOTES_LENGTH - 1).trimEnd() + "…"
        }

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
