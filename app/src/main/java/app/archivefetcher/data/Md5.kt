package app.archivefetcher.data

import java.io.InputStream
import java.security.MessageDigest

object Md5 {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Lower-case hex MD5 of the stream. [onBytes] receives the running total and [checkCancelled] is called for every
     * block, so a long hash of a multi-gigabyte file can be reported and cancelled.
     */
    fun hex(input: InputStream, checkCancelled: () -> Unit = {}, onBytes: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            checkCancelled()
            digest.update(buffer, 0, read)
            total += read
            onBytes(total)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(expected: String, actual: String): Boolean = expected.trim().equals(actual, ignoreCase = true)
}
