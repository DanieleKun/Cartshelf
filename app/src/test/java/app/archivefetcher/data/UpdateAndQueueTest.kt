package app.archivefetcher.data

import app.archivefetcher.download.DownloadQueueStore
import app.archivefetcher.download.DownloadState
import app.archivefetcher.download.DownloadTask
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateAndQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `version numbers compare as numbers`() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("2", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.1.0-beta", "1.1.0"))
    }

    private fun task(id: String, state: DownloadState) =
        DownloadTask(id, "Console", "$id.zip", "https://example.org/$id.zip", "content://folder", 100, doneBytes = 40, bytesPerSec = 9, state = state)

    @Test
    fun `the queue survives a restart and running work goes back to the queue`() {
        val file = File(folder.root, "downloads.json")
        DownloadQueueStore(file).save(
            listOf(task("a", DownloadState.Running), task("b", DownloadState.Paused), task("c", DownloadState.Done), task("d", DownloadState.Extracting)),
        )
        val restored = DownloadQueueStore(file).load().associateBy { it.id }
        assertEquals(DownloadState.Queued, restored.getValue("a").state)
        assertEquals(40L, restored.getValue("a").doneBytes)
        assertEquals(0L, restored.getValue("a").bytesPerSec)
        assertEquals(DownloadState.Paused, restored.getValue("b").state)
        assertEquals(DownloadState.Done, restored.getValue("c").state)
        assertEquals(DownloadState.Queued, restored.getValue("d").state)
        assertFalse(file.readText().contains("password", ignoreCase = true))
    }

    @Test
    fun `a missing or broken queue file starts empty`() {
        assertTrue(DownloadQueueStore(File(folder.root, "none.json")).load().isEmpty())
        val broken = File(folder.root, "broken.json").apply { writeText("{not json") }
        assertTrue(DownloadQueueStore(broken).load().isEmpty())
    }
}
