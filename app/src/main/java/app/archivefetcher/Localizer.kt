package app.archivefetcher

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import app.archivefetcher.data.Prefs
import java.util.Locale

/** Applies the in-app language choice to a context so resources resolve in that language. */
object Localizer {
    fun wrap(base: Context, language: String): Context {
        if (language == Prefs.LANGUAGE_SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(config)
    }
}

/** Gives non-UI code (downloader, API) access to localized strings that follow the user's language setting. */
class Texts(private val app: Context, private val prefs: Prefs) {
    fun get(@StringRes id: Int, vararg args: Any): String =
        Localizer.wrap(app, prefs.language).getString(id, *args)
}
