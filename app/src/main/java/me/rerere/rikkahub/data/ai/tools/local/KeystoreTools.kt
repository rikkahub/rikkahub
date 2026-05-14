package me.rerere.rikkahub.data.ai.tools.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `keystore_*` — seven LLM-callable tools over the Android Keystore. RSA-2048 keys cover
 * sign/verify; AES-256-GCM keys cover encrypt/decrypt. Keys are app-scoped (no permission
 * needed) and hardware-backed where StrongBox / TEE is available.
 */

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private val ALIAS_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val GCM_TAG_BITS = 128

private fun ksErr(msg: String) =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString()))

private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun b64decode(s: String): ByteArray = Base64.getDecoder().decode(s)

private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

/** Validates a keystore alias. Shared with the unit test. Returns null when ok. */
internal fun validateKeystoreAlias(alias: String?): String? = when {
    alias.isNullOrBlank() -> "alias is required"
    !ALIAS_REGEX.matches(alias) -> "alias must match ^[a-zA-Z0-9_-]{1,64}$"
    else -> null
}

/** Validates type + purposes consistency. Shared with the unit test. Returns null when ok. */
internal fun validateKeystoreType(type: String?, purposes: List<String>): String? {
    return when (type) {
        "rsa_2048" -> if (purposes.all { it == "sign" || it == "verify" }) null
            else "rsa_2048 supports only sign / verify purposes"
        "aes_256_gcm" -> if (purposes.all { it == "encrypt" || it == "decrypt" }) null
            else "aes_256_gcm supports only encrypt / decrypt purposes"
        else -> "type must be rsa_2048 or aes_256_gcm"
    }
}

/** Describe a stored key entry: type + purposes. Null when the alias is absent. */
private fun describeKey(ks: KeyStore, alias: String): Pair<String, List<String>>? {
    val entry = ks.getEntry(alias, null) ?: return null
    return when (entry) {
        is KeyStore.PrivateKeyEntry -> {
            val priv = entry.privateKey
            val factory = KeyFactory.getInstance(priv.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(priv, KeyInfo::class.java)
            val purposes = mutableListOf<String>()
            if (info.purposes and KeyProperties.PURPOSE_SIGN != 0) purposes.add("sign")
            if (info.purposes and KeyProperties.PURPOSE_VERIFY != 0) purposes.add("verify")
            "rsa_${info.keySize}" to purposes
        }
        is KeyStore.SecretKeyEntry -> {
            val sk = entry.secretKey
            val factory = javax.crypto.SecretKeyFactory.getInstance(sk.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(sk, KeyInfo::class.java) as KeyInfo
            val purposes = mutableListOf<String>()
            if (info.purposes and KeyProperties.PURPOSE_ENCRYPT != 0) purposes.add("encrypt")
            if (info.purposes and KeyProperties.PURPOSE_DECRYPT != 0) purposes.add("decrypt")
            "aes_${info.keySize}_gcm" to purposes
        }
        else -> null
    }
}

// ---------- keystore_generate_key ----------

fun keystoreGenerateKeyTool(): Tool = Tool(
    name = "keystore_generate_key",
    description = """
        Generate a hardware-backed key in the Android Keystore. type is "rsa_2048"
        (for sign/verify) or "aes_256_gcm" (for encrypt/decrypt). purposes must be
        consistent with the type. Returns {alias, type, purposes}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
                put("type", buildJsonObject { put("type", "string") })
                put("purposes", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("alias", "type", "purposes"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val purposes = obj["purposes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        if (purposes.isEmpty()) return@Tool ksErr("purposes is required")
        validateKeystoreType(type, purposes)?.let { return@Tool ksErr(it) }

        return@Tool try {
            when (type) {
                "rsa_2048" -> {
                    val spec = KeyGenParameterSpec.Builder(
                        alias!!,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setKeySize(2048)
                    val gen = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
                    )
                    try {
                        gen.initialize(spec.setIsStrongBoxBacked(true).build())
                        gen.generateKeyPair()
                    } catch (_: StrongBoxUnavailableException) {
                        gen.initialize(spec.setIsStrongBoxBacked(false).build())
                        gen.generateKeyPair()
                    }
                }
                "aes_256_gcm" -> {
                    val spec = KeyGenParameterSpec.Builder(
                        alias!!,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                    val gen = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
                    )
                    try {
                        gen.init(spec.setIsStrongBoxBacked(true).build())
                        gen.generateKey()
                    } catch (_: StrongBoxUnavailableException) {
                        gen.init(spec.setIsStrongBoxBacked(false).build())
                        gen.generateKey()
                    }
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("alias", alias)
                put("type", type)
                put("purposes", buildJsonArray { purposes.forEach { add(it) } })
            }.toString()))
        } catch (e: Throwable) {
            ksErr("generate_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_sign ----------

fun keystoreSignTool(): Tool = Tool(
    name = "keystore_sign",
    description = "Sign base64-encoded data with an RSA keystore key (SHA256withRSA). Returns {signature_b64}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
                put("data_b64", buildJsonObject { put("type", "string") })
            },
            required = listOf("alias", "data_b64"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        val dataB64 = obj["data_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("data_b64 is required")
        return@Tool try {
            val ks = loadKeyStore()
            val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return@Tool ksErr("no RSA key with alias '$alias'")
            val data = b64decode(dataB64)
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(entry.privateKey as PrivateKey)
                update(data)
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("signature_b64", b64encode(sig.sign()))
            }.toString()))
        } catch (e: IllegalArgumentException) {
            ksErr("data_b64 is not valid base64")
        } catch (e: Throwable) {
            ksErr("sign_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_verify ----------

fun keystoreVerifyTool(): Tool = Tool(
    name = "keystore_verify",
    description = "Verify a base64 signature over base64 data against an RSA keystore key. Returns {valid}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
                put("data_b64", buildJsonObject { put("type", "string") })
                put("signature_b64", buildJsonObject { put("type", "string") })
            },
            required = listOf("alias", "data_b64", "signature_b64"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        val dataB64 = obj["data_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("data_b64 is required")
        val sigB64 = obj["signature_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("signature_b64 is required")
        return@Tool try {
            val ks = loadKeyStore()
            val cert = ks.getCertificate(alias)
                ?: return@Tool ksErr("no RSA key with alias '$alias'")
            val publicKey: PublicKey = cert.publicKey
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(b64decode(dataB64))
            }
            val valid = sig.verify(b64decode(sigB64))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("valid", valid)
            }.toString()))
        } catch (e: IllegalArgumentException) {
            ksErr("data_b64 or signature_b64 is not valid base64")
        } catch (e: Throwable) {
            ksErr("verify_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_encrypt ----------

fun keystoreEncryptTool(): Tool = Tool(
    name = "keystore_encrypt",
    description = "Encrypt base64 plaintext with an AES-256-GCM keystore key. Returns {ciphertext_b64, iv_b64}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
                put("plaintext_b64", buildJsonObject { put("type", "string") })
            },
            required = listOf("alias", "plaintext_b64"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        val plaintextB64 = obj["plaintext_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("plaintext_b64 is required")
        return@Tool try {
            val ks = loadKeyStore()
            val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
                ?: return@Tool ksErr("no AES key with alias '$alias'")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, entry.secretKey as SecretKey)
            }
            val ciphertext = cipher.doFinal(b64decode(plaintextB64))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("ciphertext_b64", b64encode(ciphertext))
                put("iv_b64", b64encode(cipher.iv))
            }.toString()))
        } catch (e: IllegalArgumentException) {
            ksErr("plaintext_b64 is not valid base64")
        } catch (e: Throwable) {
            ksErr("encrypt_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_decrypt ----------

fun keystoreDecryptTool(): Tool = Tool(
    name = "keystore_decrypt",
    description = "Decrypt base64 ciphertext with an AES-256-GCM keystore key and its IV. Returns {plaintext_b64}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
                put("ciphertext_b64", buildJsonObject { put("type", "string") })
                put("iv_b64", buildJsonObject { put("type", "string") })
            },
            required = listOf("alias", "ciphertext_b64", "iv_b64"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        val ciphertextB64 = obj["ciphertext_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("ciphertext_b64 is required")
        val ivB64 = obj["iv_b64"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool ksErr("iv_b64 is required")
        return@Tool try {
            val ks = loadKeyStore()
            val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
                ?: return@Tool ksErr("no AES key with alias '$alias'")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    entry.secretKey as SecretKey,
                    GCMParameterSpec(GCM_TAG_BITS, b64decode(ivB64)),
                )
            }
            val plaintext = cipher.doFinal(b64decode(ciphertextB64))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("plaintext_b64", b64encode(plaintext))
            }.toString()))
        } catch (e: IllegalArgumentException) {
            ksErr("ciphertext_b64 or iv_b64 is not valid base64")
        } catch (e: Throwable) {
            ksErr("decrypt_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_delete_key ----------

fun keystoreDeleteKeyTool(): Tool = Tool(
    name = "keystore_delete_key",
    description = "Delete a key from the Android Keystore by alias. Returns {success}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("alias", buildJsonObject { put("type", "string") })
            },
            required = listOf("alias"),
        )
    },
    execute = { input ->
        val alias = input.jsonObject["alias"]?.jsonPrimitive?.contentOrNull
        validateKeystoreAlias(alias)?.let { return@Tool ksErr(it) }
        return@Tool try {
            val ks = loadKeyStore()
            if (!ks.containsAlias(alias)) {
                return@Tool ksErr("no key with alias '$alias'")
            }
            ks.deleteEntry(alias)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
            }.toString()))
        } catch (e: Throwable) {
            ksErr("delete_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)

// ---------- keystore_list_keys ----------

fun keystoreListKeysTool(): Tool = Tool(
    name = "keystore_list_keys",
    description = "List all keys in the Android Keystore. Returns {keys: [{alias, type, purposes}]}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        try {
            val ks = loadKeyStore()
            val aliases = ks.aliases().toList()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("keys", buildJsonArray {
                    aliases.forEach { alias ->
                        val described = runCatching { describeKey(ks, alias) }.getOrNull()
                        if (described != null) {
                            addJsonObject {
                                put("alias", alias)
                                put("type", described.first)
                                put("purposes", buildJsonArray { described.second.forEach { add(it) } })
                            }
                        }
                    }
                })
            }.toString()))
        } catch (e: Throwable) {
            ksErr("list_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)
