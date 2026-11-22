package app.archivefetcher

import android.app.Application
import app.archivefetcher.addon.AddonRepository
import app.archivefetcher.addon.AddonStore
import app.archivefetcher.data.ArchiveApi
import app.archivefetcher.data.ArchiveLogin
import app.archivefetcher.data.IndexStore
import app.archivefetcher.data.LaterStore
import app.archivefetcher.data.LibraryFolders
import app.archivefetcher.data.SearchIndexer
import app.archivefetcher.data.Prefs
import app.archivefetcher.data.buildHttpClient
import app.archivefetcher.download.DownloadManager
import app.archivefetcher.download.IndexScheduler
import app.archivefetcher.download.LaterRunner
import app.archivefetcher.download.LaterScheduler
import app.archivefetcher.source.SourceRegistry
import app.archivefetcher.source.WebSources
import app.archivefetcher.data.UpdateChecker
import app.archivefetcher.download.DownloadQueueStore
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.io.File
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container: small enough not to need a DI framework. */
class Graph(app: Application) {
    val prefs = Prefs(app)
    val texts = Texts(app, prefs)
    private val client = buildHttpClient({ prefs.authorizationHeader }, { prefs.cookieHeader }, allowLocalTestHosts = BuildConfig.DEBUG)
    val login = ArchiveLogin(client)

    /** Debug builds accept http addresses of the local machine so addons can be tested before they are published. */
    val addons = AddonRepository(AddonStore(File(app.filesDir, "addons"), BuildConfig.DEBUG), client, allowLocalHttp = BuildConfig.DEBUG)
    val archive = ArchiveApi(client, texts)

    /** Every kind of source an addon can point to; the Internet Archive is one of them. */
    val sources = SourceRegistry(archive, listOf(WebSources(client, allowLocalHttp = BuildConfig.DEBUG)))
    val downloads = DownloadManager(app, client, prefs, texts, DownloadQueueStore(File(app.filesDir, "downloads.json")))
    val folders = LibraryFolders(app, prefs)
    val later = LaterStore(File(app.filesDir, "later.json"))
    val index = IndexStore(File(app.filesDir, "index"))
    val indexer = SearchIndexer(sources, index)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val laterRunner = LaterRunner(folders, downloads, sources, later, appScope)
    val laterScheduler = LaterScheduler(app, prefs)
    val indexScheduler = IndexScheduler(app)
    val updates = UpdateChecker(client, prefs)

    /** Box art loader: the app's own HTTP client (https only, public hosts only) and a 200 MB disk cache. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "covers").toOkioPath()).maxSizeBytes(COVER_CACHE_BYTES).build() }
            .crossfade(true)
            .build()
    }

    private companion object {
        const val COVER_CACHE_BYTES = 200L * 1024 * 1024
    }
}

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}
