package app.archivefetcher.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Md5Test {
    @Test
    fun `known digests are produced in lower case hex`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(ByteArrayInputStream(ByteArray(0))))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test
    fun `progress reports the running total across several blocks`() {
        val data = ByteArray(200_000) { it.toByte() }
        var last = 0L
        Md5.hex(ByteArrayInputStream(data), onBytes = { last = it })
        assertEquals(200_000L, last)
    }

    @Test
    fun `comparison ignores case and surrounding spaces`() {
        assertTrue(Md5.matches(" 900150983CD24FB0D6963F7D28E17F72 ", "900150983cd24fb0d6963f7d28e17f72"))
        assertFalse(Md5.matches("00", "01"))
    }
}
