package app.archivefetcher.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.archivefetcher.data.Prefs
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** When the "download later" list is downloaded on its own. */
enum class LaterMode(val id: String) {
    /** Only when the user presses Download. */
    Manual("manual"),

    /** As soon as the device is on an unmetered network and charging. */
    Charging("charging"),

    /** At a chosen hour of the night, on an unmetered network. */
    Night("night");

    companion object {
        fun fromId(id: String?): LaterMode = entries.firstOrNull { it.id == id } ?: Manual
    }
}

class LaterScheduler(private val context: Context, private val prefs: Prefs) {
    /** Makes the system schedule match the current mode and list; call after either changes. */
    suspend fun apply(hasItems: Boolean) = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(context)
        // Replacing or cancelling would stop a run whose downloads are in progress; it reschedules nothing itself,
        // so the next change or app start applies the schedule again.
        val running = runCatching { work.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.RUNNING }
        if (running) return@withContext
        val mode = LaterMode.fromId(prefs.laterMode)
        if (mode == LaterMode.Manual || !hasItems) {
            work.cancelUniqueWork(WORK_NAME)
            return@withContext
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(mode == LaterMode.Charging)
            .build()
        val delay = if (mode == LaterMode.Night) millisUntilHour(prefs.laterHour) else 0L
        val request = OneTimeWorkRequestBuilder<LaterWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Unit
    }

    companion object {
        private const val WORK_NAME = "download-later"

        /** Time from [now] to the next occurrence of [hour]:00 (today if still ahead, otherwise tomorrow). */
        fun millisUntilHour(hour: Int, now: LocalDateTime = LocalDateTime.now()): Long {
            var target = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            val zone = ZoneId.systemDefault()
            return target.atZone(zone).toInstant().toEpochMilli() - now.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
