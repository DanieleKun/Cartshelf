package app.archivefetcher.data

import android.net.http.X509TrustManagerExtensions
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Some servers (archive.org among them) send, after their real chain, an old self-signed root and a cross-signed
 * copy of a modern root. Android 16 sometimes builds the path through that old SHA-1 root and then refuses it
 * ("Unacceptable certificate: OU=Go Daddy Class 2 Certification Authority"), so a working site fails at random.
 */
object ChainTrimmer {
    /**
     * The chain without the extras: self-signed certificates sent by the server, and everything after the first
     * certificate issued by a root the device already trusts. Nothing is ever added, so the result can only be
     * accepted if the device's own validation of it succeeds.
     */
    fun trim(chain: List<X509Certificate>, trustedRoots: Set<X500Principal>): List<X509Certificate> =
        trim(chain, trustedRoots, { it.subjectX500Principal }, { it.issuerX500Principal })

    internal fun <C, N> trim(chain: List<C>, trustedRoots: Set<N>, subject: (C) -> N, issuer: (C) -> N): List<C> {
        if (chain.size <= 1) return chain
        val withoutSelfSigned = chain.filterIndexed { i, cert -> i == 0 || subject(cert) != issuer(cert) }
        val lastNeeded = withoutSelfSigned.indexOfFirst { issuer(it) in trustedRoots }
        return if (lastNeeded >= 0) withoutSelfSigned.subList(0, lastNeeded + 1) else withoutSelfSigned
    }
}

/**
 * Validates exactly like the platform; only when the platform refuses a chain does it try once more with the chain
 * trimmed by [ChainTrimmer], again through the full platform validation (trust store, network security config,
 * certificate transparency, hostname-aware checks). It never accepts anything the platform did not accept.
 */
class ChainTrimmingTrustManager(private val platform: X509TrustManager) : X509ExtendedTrustManager() {
    private val roots: Set<X500Principal> by lazy { platform.acceptedIssuers.mapTo(HashSet()) { it.subjectX500Principal } }
    private val extensions = runCatching { X509TrustManagerExtensions(platform) }.getOrNull()

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?) {
        try {
            verify(chain, authType, host)
        } catch (refused: CertificateException) {
            val trimmed = ChainTrimmer.trim(chain.toList(), roots)
            if (trimmed.size == chain.size) throw refused
            verify(trimmed.toTypedArray(), authType, host)
        }
    }

    private fun verify(chain: Array<X509Certificate>, authType: String, host: String?) {
        if (host != null && extensions != null) extensions.checkServerTrusted(chain, authType, host)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain, authType, null)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.handshakeSession?.peerHost)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    companion object {
        /** The platform's own trust manager (system roots, network security config) wrapped, with its TLS factory. */
        fun create(): Pair<SSLSocketFactory, ChainTrimmingTrustManager> {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            val manager = ChainTrimmingTrustManager(platform)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            return context.socketFactory to manager
        }
    }
}
