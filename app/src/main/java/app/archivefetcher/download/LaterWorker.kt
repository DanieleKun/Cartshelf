package app.archivefetcher.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.archivefetcher.App
import app.archivefetcher.Localizer
import app.archivefetcher.R
import app.archivefetcher.data.Prefs
import kotlinx.coroutines.flow.first

/**
 * Runs when the chosen conditions are met (Wi-Fi and charging, or a night hour on Wi-Fi): queues everything in the
 * "download later" list and stays alive as a foreground worker until the downloads are finished.
 */
class LaterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as App).graph
        val items = graph.later.items.value
        if (items.isEmpty()) return Result.success()
        try {
            setForeground(foregroundInfo())
        } catch (_: IllegalStateException) {
            return Result.retry()
        }
        graph.laterRunner.start(items)
        // Downloads run in DownloadManager; keep this worker (and the process) alive until they are over.
        graph.downloads.tasks.first { tasks -> tasks.none { it.isActive } }
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val localized = Localizer.wrap(applicationContext, Prefs(applicationContext).language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, localized.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(localized.getString(R.string.notif_later))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "later_downloads"
        const val NOTIFICATION_ID = 42
    }
}
