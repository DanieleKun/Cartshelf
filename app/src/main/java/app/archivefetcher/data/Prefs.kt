package app.archivefetcher.data

import android.content.Context
import androidx.core.content.edit

/**
 * Small key/value store for user settings. Internet Archive keys, the session cookies and the account email are
 * encrypted with a key kept in the Android Keystore (see [SecretCipher]); nothing leaves the device.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val cipher = SecretCipher()

    /** Decrypted secrets, so a request does not pay for a Keystore call every time. */
    private val secretCache = HashMap<String, String>()

    var maxParallel: Int
        get() = sp.getInt(KEY_PARALLEL, DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)
        set(value) = sp.edit { putInt(KEY_PARALLEL, value.coerceIn(1, MAX_PARALLEL)) }

    var accessKey: String
        get() = readSecret(KEY_ACCESS)
        set(value) = writeSecret(KEY_ACCESS, value.trim())

    var secretKey: String
        get() = readSecret(KEY_SECRET)
        set(value) = writeSecret(KEY_SECRET, value.trim())

    /** Values saved in plain text by older versions are encrypted the first time they are read. */
    private fun readSecret(key: String): String = synchronized(secretCache) {
        secretCache[key]?.let { return it }
        val stored = sp.getString(key, "").orEmpty()
        val value = when {
            stored.isEmpty() -> ""
            SecretCipher.isEncrypted(stored) -> cipher.decrypt(stored).orEmpty()
            else -> stored.also { writeSecret(key, it) }
        }
        secretCache[key] = value
        value
    }

    /**
     * If the Keystore is unusable the new value is kept for this session only and never written in plain text;
     * whatever was stored before is left alone, so a Keystore hiccup can never wipe the user's saved credentials.
     */
    private fun writeSecret(key: String, value: String) = synchronized(secretCache) {
        secretCache[key] = value
        if (value.isEmpty()) {
            sp.edit { remove(key) }
            return@synchronized
        }
        val sealed = runCatching { cipher.encrypt(value) }.getOrNull() ?: return@synchronized
        sp.edit { putString(key, sealed) }
    }

    /** "system" follows the device language; otherwise a language code such as "en" or "it". */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        set(value) = sp.edit { putString(KEY_LANGUAGE, value) }

    /** Unpack ZIP files into the destination folder once they are downloaded. */
    var extractZips: Boolean
        get() = sp.getBoolean(KEY_EXTRACT, true)
        set(value) = sp.edit { putBoolean(KEY_EXTRACT, value) }

    /** Remove the ZIP once it was unpacked successfully. */
    var deleteZipAfterExtract: Boolean
        get() = sp.getBoolean(KEY_DELETE_ZIP, true)
        set(value) = sp.edit { putBoolean(KEY_DELETE_ZIP, value) }

    /** Library folder (a SAF tree address) that holds one sub-folder per console. */
    var baseFolder: String?
        get() = sp.getString(KEY_BASE_FOLDER, null)
        set(value) = sp.edit { if (value == null) remove(KEY_BASE_FOLDER) else putString(KEY_BASE_FOLDER, value) }

    /** Id of the [FrontendLayout] used to name the console sub-folders. */
    var layoutId: String
        get() = sp.getString(KEY_LAYOUT, FrontendLayout.Names.id) ?: FrontendLayout.Names.id
        set(value) = sp.edit { putString(KEY_LAYOUT, value) }

    /** Compare each finished download with the checksum Internet Archive publishes for it. */
    var verifyDownloads: Boolean
        get() = sp.getBoolean(KEY_VERIFY, true)
        set(value) = sp.edit { putBoolean(KEY_VERIFY, value) }

    /** How the "download later" list is started: see [app.archivefetcher.download.LaterMode]. */
    var laterMode: String
        get() = sp.getString(KEY_LATER_MODE, "manual") ?: "manual"
        set(value) = sp.edit { putString(KEY_LATER_MODE, value) }

    /** Hour of the night (0-23) used by the night mode. */
    var laterHour: Int
        get() = sp.getInt(KEY_LATER_HOUR, 2).coerceIn(0, 23)
        set(value) = sp.edit { putInt(KEY_LATER_HOUR, value.coerceIn(0, 23)) }

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDING, value) }

    val hasCredentials: Boolean get() = accessKey.isNotBlank() && secretKey.isNotBlank()

    /** Session cookies from an account login; the account password itself is never stored. */
    var loginCookies: String
        get() = readSecret(KEY_COOKIES)
        private set(value) = writeSecret(KEY_COOKIES, value)

    var loginEmail: String
        get() = readSecret(KEY_LOGIN_EMAIL)
        private set(value) = writeSecret(KEY_LOGIN_EMAIL, value)

    val hasLogin: Boolean get() = loginCookies.isNotBlank()

    /** True when any kind of Internet Archive credentials are available. */
    val hasAuth: Boolean get() = hasCredentials || hasLogin

    val authorizationHeader: String? get() = if (hasCredentials) "LOW $accessKey:$secretKey" else null

    val cookieHeader: String? get() = loginCookies.takeIf { it.isNotBlank() }

    fun saveLogin(email: String, cookies: Map<String, String>) {
        loginEmail = email
        loginCookies = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun clearLogin() {
        loginEmail = ""
        loginCookies = ""
    }

    /** Destination folder (a SAF tree address) chosen for a console, keyed by the console id. */
    fun folderFor(consoleId: String): String? = sp.getString(folderKey(consoleId), null)

    fun setFolder(consoleId: String, treeUri: String) =
        sp.edit { putString(folderKey(consoleId), treeUri) }

    private fun folderKey(consoleId: String) = "folder:$consoleId"

    companion object {
        const val DEFAULT_PARALLEL = 2
        const val LANGUAGE_SYSTEM = "system"
        const val KEYS_PAGE_URL = "https://archive.org/account/s3.php"

        /** Kept low on purpose: Internet Archive throttles aggressive clients. */
        const val MAX_PARALLEL = 3
        private const val KEY_BASE_FOLDER = "base_folder"
        private const val KEY_LAYOUT = "folder_layout"
        private const val KEY_LATER_MODE = "later_mode"
        private const val KEY_LATER_HOUR = "later_hour"
        private const val KEY_VERIFY = "verify_downloads"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_PARALLEL = "max_parallel"
        private const val KEY_ACCESS = "ia_access"
        private const val KEY_SECRET = "ia_secret"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_COOKIES = "ia_cookies"
        private const val KEY_LOGIN_EMAIL = "ia_login_email"
        private const val KEY_EXTRACT = "extract_zips"
        private const val KEY_DELETE_ZIP = "delete_zip_after_extract"
    }
}
