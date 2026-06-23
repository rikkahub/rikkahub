package me.rerere.rikkahub.data.network

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The curl `-k` gate ([insecureAwareTrustManager]). The platform fake REJECTS every server cert, so a
 * non-throwing `checkServerTrusted` proves the bypass engaged. Pins: default-off delegates (secure),
 * on skips, the toggle is read LIVE per check (no rebuild), and client-trust / accepted-issuers always
 * delegate.
 */
class InsecureAwareTlsTest {

    private val issuers = arrayOf<X509Certificate>()
    private val platform = object : X509TrustManager {
        var clientChecked = false
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            clientChecked = true
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("untrusted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = issuers
    }

    @Test
    fun `secure mode (default off) delegates and rejects an untrusted server cert`() {
        val tm = insecureAwareTrustManager(platform) { false }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(null, "RSA") }
    }

    @Test
    fun `insecure mode skips server certificate verification`() {
        val tm = insecureAwareTrustManager(platform) { true }
        tm.checkServerTrusted(null, "RSA") // must NOT throw — the curl -k bypass
    }

    @Test
    fun `the toggle is read live per check, no client rebuild required`() {
        var allow = false
        val tm = insecureAwareTrustManager(platform) { allow }

        assertThrows("off => rejected", CertificateException::class.java) { tm.checkServerTrusted(null, "RSA") }
        allow = true
        tm.checkServerTrusted(null, "RSA") // flipping on takes effect immediately
        allow = false
        assertThrows("off again => rejected", CertificateException::class.java) { tm.checkServerTrusted(null, "RSA") }
    }

    @Test
    fun `client trust and accepted issuers always delegate, even when insecure`() {
        val tm = insecureAwareTrustManager(platform) { true }
        assertSame("accepted issuers must come from the platform store", issuers, tm.acceptedIssuers)
        tm.checkClientTrusted(null, "RSA")
        assertTrue("client trust delegated to the platform manager", platform.clientChecked)
    }
}
