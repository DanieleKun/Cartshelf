package app.archivefetcher.download

import kotlinx.serialization.Serializable

enum class DownloadState { Queued, Running, Verifying, Extracting, Paused, Done, Failed }

/** One download of the queue; serializable so the queue can be saved (see [DownloadQueueStore]). */
@Serializable
data class DownloadTask(
    val id: String,
    val consoleName: String,
    val fileName: String,
    val url: String,
    val folderUri: String,
    val totalBytes: Long,
    /** Checksum to compare the finished file with, when Internet Archive publishes one. */
    val md5: String? = null,
    val verified: Boolean = false,
    /** Key of the "download later" entry this task came from, if any. */
    val laterKey: String? = null,
    val doneBytes: Long = 0,
    val bytesPerSec: Long = 0,
    val state: DownloadState = DownloadState.Queued,
    /** 0..1 while the finished file is being checked or a ZIP archive is being unpacked. */
    val extractProgress: Float = 0f,
    /** Human readable status: failure reason, or a waiting note such as a throttling back-off. */
    val message: String? = null,
) {
    val isActive: Boolean
        get() = state == DownloadState.Queued || state == DownloadState.Running || state == DownloadState.Verifying ||
            state == DownloadState.Extracting

    val progress: Float
        get() = when {
            (state == DownloadState.Extracting || state == DownloadState.Verifying) -> extractProgress.coerceIn(0f, 1f)
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}
