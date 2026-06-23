package me.rerere.rikkahub.data.network

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Optional, default-off TLS-verification bypass — the curl `-k` / `--insecure` equivalent for the
 * shared HTTP client (#Advanced > Security). When [allowInsecure] returns true AT HANDSHAKE TIME the
 * server-certificate and hostname checks are skipped; otherwise every check delegates to the platform
 * default trust store + the standard hostname verifier. [allowInsecure] is read live (it closes over
 * the user setting), so the toggle takes effect on the next connection without an app restart, and the
 * DEFAULT-OFF path is exactly the platform-secure behaviour (production unchanged).
 *
 * SECURITY: turning the toggle on disables MITM protection for this client. It is a deliberate
 * escape hatch for self-signed / dev provider endpoints, gated behind an explicit, default-off,
 * user-opted setting — never on by default, never silent.
 */

/** The platform default X509 trust manager (the system CA trust store). */
internal fun platformX509TrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
}

/**
 * A trust manager that delegates to [platform] EXCEPT when [allowInsecure] is true at check time, in
 * which case it skips server-certificate verification. Client-trust and accepted-issuers always
 * delegate. Pure + injectable so the gating is unit-testable without a real socket.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal fun insecureAwareTrustManager(
    platform: X509TrustManager,
    allowInsecure: () -> Boolean,
): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Default-off: delegate to the platform trust store. Only when the user has opted in does
        // this become a no-op (accept any server certificate, like curl -k).
        if (!allowInsecure()) platform.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/**
 * Install the insecure-aware TLS trust + hostname policy on this builder. Returns the same builder.
 */
@SuppressLint("BadHostnameVerifier", "AllowAllHostnameVerifier")
fun OkHttpClient.Builder.insecureAware(allowInsecure: () -> Boolean): OkHttpClient.Builder {
    val platform = platformX509TrustManager()
    val trustManager = insecureAwareTrustManager(platform, allowInsecure)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)

    // Capture OkHttp's own default hostname verifier so the secure path is byte-for-byte the standard
    // behaviour; only bypass it when the user opted in (curl -k also drops hostname checking).
    val defaultVerifier = OkHttpClient().hostnameVerifier
    hostnameVerifier(HostnameVerifier { hostname, session ->
        allowInsecure() || defaultVerifier.verify(hostname, session)
    })
    return this
}
