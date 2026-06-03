package me.rerere.ai.provider.providers.vertex

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class ServiceAccountTokenProviderCacheKeyTest {
    private lateinit var provider: ServiceAccountTokenProvider

    @Before
    fun setUp() {
        provider = ServiceAccountTokenProvider(OkHttpClient())
    }

    private fun cacheKey(email: String, scopes: List<String>, privateKeyPem: String): String {
        val method = ServiceAccountTokenProvider::class.java.getDeclaredMethod(
            "generateCacheKey",
            String::class.java,
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, email, scopes, privateKeyPem) as String
    }

    private val email = "svc@project-id.iam.gserviceaccount.com"
    private val scopes = listOf("https://www.googleapis.com/auth/cloud-platform")
    private val pemA = "-----BEGIN PRIVATE KEY-----\nAAAAkeyMaterialOldRevoked==\n-----END PRIVATE KEY-----"
    private val pemB = "-----BEGIN PRIVATE KEY-----\nBBBBkeyMaterialNewRotated==\n-----END PRIVATE KEY-----"

    @Test
    fun `rotated private key with same email and scopes yields a different cache key`() {
        // Issue #26 regression: a key rotation must not serve a token signed by the old key.
        val keyOld = cacheKey(email, scopes, pemA)
        val keyNew = cacheKey(email, scopes, pemB)
        assertNotEquals(keyOld, keyNew)
    }

    @Test
    fun `identical credentials yield an equal cache key`() {
        // Caching must still hit for the same credential, no regression.
        assertEquals(cacheKey(email, scopes, pemA), cacheKey(email, scopes, pemA))
    }

    @Test
    fun `cache key does not contain the raw private key material`() {
        val key = cacheKey(email, scopes, pemA)
        assertFalse(key.contains(pemA))
        assertFalse(key.contains("keyMaterialOldRevoked"))
    }
}
