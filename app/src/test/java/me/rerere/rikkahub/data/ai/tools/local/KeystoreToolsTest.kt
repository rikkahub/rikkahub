package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 25 — `keystore_*` validation. The actual AndroidKeyStore operations need an
 * instrumented test; this covers the pure validation functions + the tools' early-return
 * validation paths (which run before any KeyStore call).
 */
class KeystoreToolsTest {

    @Test fun `alias validation rejects blank`() {
        assertEquals("alias is required", validateKeystoreAlias(null))
        assertEquals("alias is required", validateKeystoreAlias(""))
    }

    @Test fun `alias validation rejects illegal characters`() {
        assertNotNull(validateKeystoreAlias("bad alias"))
        assertNotNull(validateKeystoreAlias("bad/alias"))
        assertNotNull(validateKeystoreAlias("a".repeat(65)))
    }

    @Test fun `alias validation accepts legal aliases`() {
        assertNull(validateKeystoreAlias("my_key-1"))
        assertNull(validateKeystoreAlias("a"))
        assertNull(validateKeystoreAlias("a".repeat(64)))
    }

    @Test fun `type validation enforces rsa purpose consistency`() {
        assertNull(validateKeystoreType("rsa_2048", listOf("sign", "verify")))
        assertNotNull(validateKeystoreType("rsa_2048", listOf("encrypt")))
    }

    @Test fun `type validation enforces aes purpose consistency`() {
        assertNull(validateKeystoreType("aes_256_gcm", listOf("encrypt", "decrypt")))
        assertNotNull(validateKeystoreType("aes_256_gcm", listOf("sign")))
    }

    @Test fun `type validation rejects unknown type`() {
        assertEquals(
            "type must be rsa_2048 or aes_256_gcm",
            validateKeystoreType("ec_p256", listOf("sign")),
        )
    }

    @Test fun `generate_key tool early-returns on bad alias`() {
        val out = execTool(
            keystoreGenerateKeyTool(),
            """{"alias":"bad alias","type":"rsa_2048","purposes":["sign"]}""",
        )
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(
            "alias must match ^[a-zA-Z0-9_-]{1,64}$",
            obj["error"]?.jsonPrimitive?.content,
        )
    }

    @Test fun `generate_key tool early-returns on inconsistent purposes`() {
        val out = execTool(
            keystoreGenerateKeyTool(),
            """{"alias":"k1","type":"rsa_2048","purposes":["encrypt"]}""",
        )
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(
            "rsa_2048 supports only sign / verify purposes",
            obj["error"]?.jsonPrimitive?.content,
        )
    }

    @Test fun `sign tool early-returns on missing data`() {
        val out = execTool(keystoreSignTool(), """{"alias":"k1"}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("data_b64 is required", obj["error"]?.jsonPrimitive?.content)
    }

    @Test fun `delete_key tool early-returns on bad alias`() {
        val out = execTool(keystoreDeleteKeyTool(), """{"alias":""}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("alias is required", obj["error"]?.jsonPrimitive?.content)
    }
}
