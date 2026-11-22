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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container: small enough not to need a DI framework. */
class Graph(app: Application) {
    val prefs = Prefs(app)
    val texts = Texts(app, prefs)
    private val client = buildHttpClient({ prefs.authorizationHeader }, { prefs.cookieHeader })
    val login = ArchiveLogin(client)

    /** Debug builds accept http addresses of the local machine so addons can be tested before they are published. */
    val addons = AddonRepository(AddonStore(File(app.filesDir, "addons")), client, allowLocalHttp = BuildConfig.DEBUG)
    val archive = ArchiveApi(client, texts)
    val downloads = DownloadManager(app, client, prefs, texts)
    val folders = LibraryFolders(app, prefs)
    val later = LaterStore(File(app.filesDir, "later.json"))
    val index = IndexStore(File(app.filesDir, "index"))
    val indexer = SearchIndexer(archive, index)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val laterRunner = LaterRunner(folders, downloads, archive, later, appScope)
    val laterScheduler = LaterScheduler(app, prefs)
    val indexScheduler = IndexScheduler(app)
}

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}
