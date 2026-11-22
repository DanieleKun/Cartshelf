package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainTrimmerTest {
    private data class Cert(val subject: String, val issuer: String)

    private fun trim(chain: List<Cert>, roots: Set<String>) = ChainTrimmer.trim(chain, roots, Cert::subject, Cert::issuer)

    private val leaf = Cert("archive.org", "GoDaddy Secure CA G2")
    private val intermediate = Cert("GoDaddy Secure CA G2", "GoDaddy Root G2")
    private val crossSigned = Cert("GoDaddy Root G2", "GoDaddy Class 2")
    private val oldRoot = Cert("GoDaddy Class 2", "GoDaddy Class 2")

    @Test
    fun `the old root and the cross-signed copy are dropped`() {
        val sent = listOf(leaf, intermediate, crossSigned, oldRoot)
        assertEquals(listOf(leaf, intermediate), trim(sent, setOf("GoDaddy Root G2", "GoDaddy Class 2")))
    }

    @Test
    fun `self-signed extras go even when no trusted issuer is found`() {
        assertEquals(listOf(leaf, intermediate, crossSigned), trim(listOf(leaf, intermediate, crossSigned, oldRoot), emptySet()))
    }

    @Test
    fun `a clean chain stays as it is`() {
        assertEquals(listOf(leaf, intermediate), trim(listOf(leaf, intermediate), setOf("GoDaddy Root G2")))
    }

    @Test
    fun `a self-signed leaf is kept`() {
        val self = Cert("host", "host")
        assertEquals(listOf(self), trim(listOf(self), emptySet()))
        assertEquals(listOf(self), trim(listOf(self, oldRoot), emptySet()))
    }
}
