package app.archivefetcher.data

import app.archivefetcher.download.DownloadQueueStore
import app.archivefetcher.download.DownloadState
import app.archivefetcher.download.DownloadTask
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    private val page = ReceiverPage("T", "Intro <b>", "Url", "Send", "File", "Sent", "Expired")

    @Test
    fun `the phone page escapes its texts`() {
        val html = page.html(sent = false)
        assertTrue(html.contains("Intro &lt;b&gt;"))
        assertTrue(page.html(sent = true).contains("Sent"))
    }

    @Test
    fun `the receiver answers only on its secret path and hands over what was sent`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        val receiver = AddonReceiver(scope, deliver = Dispatchers.IO)
        val address = receiver.start(page) { received.add(it) }
        assumeTrue("no local network address on this machine", address != null)
        try {
            val wrong = URL(address!!.substringBeforeLast('/') + "/nope").openConnection() as HttpURLConnection
            assertEquals(404, wrong.responseCode)

            val get = URL(address).openConnection() as HttpURLConnection
            assertEquals(200, get.responseCode)
            assertTrue(get.inputStream.readBytes().toString(Charsets.UTF_8).contains("Intro"))

            val post = URL(address).openConnection() as HttpURLConnection
            post.requestMethod = "POST"
            post.doOutput = true
            post.outputStream.use { it.write("https://example.org/my.addon.json".toByteArray()) }
            assertEquals(200, post.responseCode)
            assertEquals("https://example.org/my.addon.json", received.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            receiver.stop()
            scope.cancel()
        }
    }

    @Test
    fun `the receiver stops by itself when someone keeps guessing the address`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val receiver = AddonReceiver(scope, deliver = Dispatchers.IO)
        val address = receiver.start(page, onStopped = { stopped.countDown() }) { }
        assumeTrue("no local network address on this machine", address != null)
        try {
            val base = address!!.substringBeforeLast('/')
            repeat(20) { i ->
                val wrong = URL("$base/guess$i").openConnection() as HttpURLConnection
                assertEquals(404, wrong.responseCode)
            }
            assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            receiver.stop()
            scope.cancel()
        }
    }
}
