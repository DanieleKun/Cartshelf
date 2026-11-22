package app.archivefetcher.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.archivefetcher.App
import app.archivefetcher.Localizer
import app.archivefetcher.R
import app.archivefetcher.data.IndexProgress
import app.archivefetcher.data.Prefs
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Builds the search index as a foreground job, so it keeps going with the screen off or the app closed.
 *
 * Every console is saved as soon as it is read and a run only reads the consoles still missing, so when Android stops
 * the job (no network, low memory, reboot) WorkManager starts it again later and it continues where it stopped.
 */
@OptIn(FlowPreview::class)
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val localized: Context get() = Localizer.wrap(applicationContext, Prefs(applicationContext).language)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        val graph = (applicationContext as App).graph
        try {
            setForeground(foregroundInfo(null))
        } catch (_: IllegalStateException) {
            // Android refused a foreground job right now (app in background on Android 12+): try again soon.
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        // The job may run in a fresh process where the screens never loaded the addons.
        if (graph.addons.addons.value.isEmpty()) graph.addons.load()
        val consoles = graph.addons.catalog.value.categories.flatMap { it.consoles }
        if (consoles.isEmpty()) return Result.success()

        val wifi = acquireWifiLock()
        return try {
            val unread = coroutineScope {
                val notifier = launch {
                    graph.indexer.progress.sample(NOTIFY_EVERY_MS).collect { progress ->
                        if (progress.running) notify(foregroundInfo(progress))
                    }
                }
                val full = inputData.getBoolean(KEY_FULL, false) && runAttemptCount == 0
                graph.indexer.build(consoles, onlyMissing = !full).also { notifier.cancel() }
            }
            // Consoles that could not be read at all (connection lost) are retried later, resuming from them.
            if (unread > 0 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            runCatching { if (wifi?.isHeld == true) wifi.release() }
        }
    }

    private fun notify(info: ForegroundInfo) {
        applicationContext.getSystemService(NotificationManager::class.java)?.notify(info.notificationId, info.notification)
    }

    private fun foregroundInfo(progress: IndexProgress?): ForegroundInfo {
        val text = localized
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, text.getString(R.string.channel_index), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(text.getString(R.string.index_notif_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, text.getString(R.string.index_stop), stop)
        if (progress == null || progress.totalConsoles == 0) {
            builder.setContentText(text.getString(R.string.index_notif_preparing)).setProgress(0, 0, true)
        } else {
            builder.setContentText(
                text.getString(
                    R.string.index_notif_progress,
                    (progress.doneConsoles + 1).coerceAtMost(progress.totalConsoles), progress.totalConsoles,
                    progress.currentConsole.orEmpty(),
                ),
            ).setProgress(PROGRESS_MAX, (progress.fraction * PROGRESS_MAX).toInt(), false)
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Keeps Wi-Fi at full speed with the screen off; WorkManager already holds the CPU wake lock. */
    private fun acquireWifiLock(): WifiManager.WifiLock? = runCatching {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifi?.createWifiLock(mode, "archivefetcher:index")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private companion object {
        const val CHANNEL_ID = "search_index"
        const val NOTIFICATION_ID = 43
        const val PROGRESS_MAX = 1000
        const val NOTIFY_EVERY_MS = 1000L
        const val MAX_ATTEMPTS = 8
    }
}

/** Input flag: read every console again instead of only the missing ones. */
internal const val KEY_FULL = "full"

/** What the search screen shows about the background job. */
enum class IndexJobState { Idle, Waiting, Running }

class IndexScheduler(private val context: Context) {
    private val work get() = WorkManager.getInstance(context)

    /**
     * Starts the job, or leaves the one already queued or running alone. With [full] every console is read again and
     * overwritten one at a time, so the existing index keeps answering searches while it is refreshed; after an
     * interruption the retry only completes what is missing.
     */
    fun start(full: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putBoolean(KEY_FULL, full).build())
            .build()
        work.enqueueUniqueWork(WORK_NAME, if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun stop() {
        work.cancelUniqueWork(WORK_NAME)
    }

    val state: Flow<IndexJobState> get() = work.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        when {
            infos.any { it.state == WorkInfo.State.RUNNING } -> IndexJobState.Running
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> IndexJobState.Waiting
            else -> IndexJobState.Idle
        }
    }

    private companion object {
        const val WORK_NAME = "search-index"
        const val BACKOFF_SECONDS = 30L
    }
}
