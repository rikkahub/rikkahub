package me.rerere.ai.provider.providers.vertex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the JWT claim/header construction in
 * [ServiceAccountTokenProvider]. These guard against JSON injection / malformed
 * JSON when a service-account email (attacker-influenceable user input) contains
 * characters that must be escaped — the original string-template implementation
 * interpolated the email raw, allowing claim injection.
 */
class ServiceAccountTokenProviderJwtTest {

    private val provider = ServiceAccountTokenProvider(OkHttpClient())

    private val cloudPlatform = "https://www.googleapis.com/auth/cloud-platform"

    @Test
    fun `email containing a double-quote does not inject or override claims`() {
        // Crafted to inject an extra `scope` claim and an extra top-level key
        // under the raw string-template implementation.
        val maliciousEmail =
            "evil\",\"scope\":\"$cloudPlatform admin\",\"x\":\"@p.iam.gserviceaccount.com"
        val scopes = listOf(cloudPlatform)

        val claim = provider.buildJwtClaim(maliciousEmail, scopes, iat = 100L, exp = 200L)

        // Must be valid, parseable JSON (raw template would emit broken JSON here).
        val obj = Json.parseToJsonElement(claim).jsonObject

        // iss round-trips to the exact malicious literal — proving it was escaped,
        // not interpreted as JSON structure.
        assertEquals(maliciousEmail, obj["iss"]?.jsonPrimitive?.content)

        // scope is exactly the legitimate space-joined scopes — NOT the injected one.
        assertEquals(cloudPlatform, obj["scope"]?.jsonPrimitive?.content)

        // No injected top-level key leaked through.
        assertNull(obj["x"])
        assertEquals(setOf("iss", "scope", "aud", "iat", "exp"), obj.keys)
    }

    @Test
    fun `email containing newline and backslash escapes cleanly and round-trips`() {
        val email = "a\nb\\c@p.iam.gserviceaccount.com"

        val claim = provider.buildJwtClaim(email, listOf(cloudPlatform), iat = 1L, exp = 2L)

        val obj = Json.parseToJsonElement(claim).jsonObject
        assertEquals(email, obj["iss"]?.jsonPrimitive?.content)
    }

    @Test
    fun `happy path produces correct scope aud iat and exp`() {
        val email = "svc@my-project.iam.gserviceaccount.com"
        val scopes = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/devstorage.read_only"
        )

        val claim = provider.buildJwtClaim(email, scopes, iat = 1700000000L, exp = 1700003600L)

        val obj = Json.parseToJsonElement(claim).jsonObject
        assertEquals(email, obj["iss"]?.jsonPrimitive?.content)
        assertEquals(scopes.joinToString(" "), obj["scope"]?.jsonPrimitive?.content)
        assertEquals("https://oauth2.googleapis.com/token", obj["aud"]?.jsonPrimitive?.content)
        assertEquals(1700000000L, obj["iat"]?.jsonPrimitive?.long)
        assertEquals(1700003600L, obj["exp"]?.jsonPrimitive?.long)
    }

    @Test
    fun `header is well-formed and contains RS256 and JWT`() {
        val obj = Json.parseToJsonElement(provider.buildJwtHeader()).jsonObject
        assertEquals("RS256", obj["alg"]?.jsonPrimitive?.content)
        assertEquals("JWT", obj["typ"]?.jsonPrimitive?.content)
    }
}
