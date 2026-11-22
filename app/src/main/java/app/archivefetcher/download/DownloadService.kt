package app.archivefetcher.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.archivefetcher.App
import app.archivefetcher.Localizer
import app.archivefetcher.R
import app.archivefetcher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows a progress notification while downloads are active.
 * It also holds a partial wake lock and a Wi-Fi lock so transfers continue with the screen off.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var observer: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localizer.wrap(newBase, Prefs(newBase).language))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        createChannel()
        try {
            showForeground(buildNotification(activeCount = 0, progress = 0))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: pause instead of crashing, the user can resume later.
            (application as App).graph.downloads.pauseAll()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        holdLocks()
        if (observer == null) {
            val manager = (application as App).graph.downloads
            observer = scope.launch {
                manager.tasks.collect { tasks ->
                    val active = tasks.filter { it.isActive }
                    if (active.isEmpty()) {
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Only stops if no newer start request arrived in the meantime.
                        stopSelf(lastStartId)
                    } else {
                        holdLocks()
                        val sized = active.filter { it.totalBytes > 0 }
                        val total = sized.sumOf { it.totalBytes }.coerceAtLeast(1)
                        val done = sized.sumOf { it.doneBytes.coerceAtMost(it.totalBytes) }
                        val percent = (done * 100 / total).toInt()
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(active.size, percent))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits how long a data-sync service may run; pause cleanly so the user can resume. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as App).graph.downloads.pauseAll()
        releaseLocks()
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        observer = null
        super.onDestroy()
    }

    private fun holdLocks() {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_HOLD_MS)
            }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = runCatching {
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifi?.createWifiLock(mode, LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun showForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(activeCount: Int, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (activeCount == 0) getString(R.string.notif_preparing)
                else getString(R.string.notif_progress, activeCount, progress),
            )
            .setProgress(100, progress, activeCount == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
        const val LOCK_TAG = "archivefetcher:downloads"

        /** Safety net so a bug can never keep the CPU awake forever; renewed while downloads are active. */
        const val MAX_LOCK_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
