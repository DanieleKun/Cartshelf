package app.archivefetcher.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZipExtractorTest {
    private class MemorySink : ExtractionSink {
        val files = LinkedHashMap<String, ByteArray>()
        val directories = ArrayList<String>()

        override fun makeDirectory(path: List<String>) {
            directories += path.joinToString("/")
        }

        override fun openFile(path: List<String>): OutputStream {
            val key = path.joinToString("/")
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    files[key] = toByteArray()
                }
            }
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                if (data != null) zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `extracts nested files and folders`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val archive = zip("game/" to null, "game/disc.iso" to payload, "readme.txt" to "hi".toByteArray())
        val sink = MemorySink()

        val result = ZipExtractor().extract(ByteArrayInputStream(archive), sink)

        assertEquals(listOf("game/disc.iso", "readme.txt"), result)
        assertEquals(listOf("game"), sink.directories)
        assertArrayEquals(payload, sink.files.getValue("game/disc.iso"))
        assertEquals("hi", String(sink.files.getValue("readme.txt")))
    }

    @Test
    fun `refuses entries that try to escape the destination`() {
        val archive = zip("../evil.txt" to "x".toByteArray())
        try {
            ZipExtractor().extract(ByteArrayInputStream(archive), MemorySink())
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("Unsafe"))
        }
    }

    @Test
    fun `backslashes and leading slashes stay inside the destination`() {
        assertEquals(listOf("a", "b.txt"), ZipExtractor.safePath("/a\\b.txt"))
        assertTrue(ZipExtractor.safePath("./").isEmpty())
    }

    @Test
    fun `an empty archive extracts nothing`() {
        assertTrue(ZipExtractor().extract(ByteArrayInputStream(zip()), MemorySink()).isEmpty())
    }

    @Test
    fun `cancellation aborts the extraction`() {
        val archive = zip("a.bin" to ByteArray(500_000), "b.bin" to ByteArray(10))
        val sink = MemorySink()
        var calls = 0
        try {
            ZipExtractor().extract(ByteArrayInputStream(archive), sink, checkCancelled = {
                if (++calls > 3) throw IllegalStateException("cancelled")
            })
            fail("expected cancellation")
        } catch (expected: IllegalStateException) {
            assertTrue(sink.files.size < 2)
        }
    }

    @Test
    fun `counting stream reports consumed bytes`() {
        val archive = zip("a.bin" to ByteArray(100_000))
        val counting = CountingInputStream(ByteArrayInputStream(archive))
        ZipExtractor().extract(counting, MemorySink())
        assertTrue(counting.count > 0)
    }
}
