package me.rerere.rikkahub.skills.js

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "SkillSecretsStore"

/**
 * Phase 18 — encrypted per-skill secret storage. Skills that hit a third-party API
 * (Wikipedia behind auth, OpenWeatherMap, etc.) ask the user for an API key which we
 * persist locally encrypted. The `run_js` tool reads via [get] when the LLM passes
 * `secret_key` in args.
 *
 * Encryption: AES/GCM with an Android-Keystore-backed master key. Falls back to
 * obfuscated plaintext if the keystore is unavailable (very old / rooted devices) so the
 * user-flow doesn't break — we log loudly in that case.
 *
 * Key per (skillName, secretName) so different skills can't read each other's secrets.
 * Backup is excluded (keys aren't transferable; re-entry on new device is intentional).
 */
class SkillSecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun set(skillName: String, secretName: String, value: String) {
        val (encrypted, iv) = encrypt(value)
        prefs.edit {
            putString(prefKey(skillName, secretName), encrypted)
            putString(ivKey(skillName, secretName), iv)
        }
    }

    fun get(skillName: String, secretName: String): String? {
        val encrypted = prefs.getString(prefKey(skillName, secretName), null) ?: return null
        val iv = prefs.getString(ivKey(skillName, secretName), null) ?: return null
        return decrypt(encrypted, iv)
    }

    fun remove(skillName: String, secretName: String) {
        prefs.edit {
            remove(prefKey(skillName, secretName))
            remove(ivKey(skillName, secretName))
        }
    }

    /** All (skillName, secretName) pairs the user currently has stored. */
    fun list(): List<Pair<String, String>> {
        val all = prefs.all.keys
            .filter { it.startsWith("skill_secret_") && !it.startsWith("skill_secret_iv_") }
        return all.mapNotNull { k ->
            val rest = k.removePrefix("skill_secret_")
            val sep = rest.indexOf("__")
            if (sep <= 0) null else rest.substring(0, sep) to rest.substring(sep + 2)
        }
    }

    /** Remove every secret stored for a skill — used when the user uninstalls it. */
    fun removeAllForSkill(skillName: String) {
        val toRemove = prefs.all.keys.filter {
            it.startsWith("skill_secret_$skillName" + "__") ||
                it.startsWith("skill_secret_iv_$skillName" + "__")
        }
        if (toRemove.isEmpty()) return
        prefs.edit { toRemove.forEach { remove(it) } }
    }

    // -- crypto -------------------------------------------------------------

    private fun encrypt(plain: String): Pair<String, String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        encode(encrypted) to encode(iv)
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore-backed encrypt failed; falling back to obfuscated plaintext", t)
        encode(plain.toByteArray(StandardCharsets.UTF_8)) to FALLBACK_IV_MARKER
    }

    private fun decrypt(encrypted: String, iv: String): String? = try {
        if (iv == FALLBACK_IV_MARKER) {
            String(decode(encrypted), StandardCharsets.UTF_8)
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(encrypted)), StandardCharsets.UTF_8)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Decrypt failed", t)
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun decode(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private fun prefKey(skill: String, name: String): String = "skill_secret_${skill}__${name}"
    private fun ivKey(skill: String, name: String): String = "skill_secret_iv_${skill}__${name}"

    companion object {
        private const val PREFS_NAME = "skill_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rikkahub_skill_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FALLBACK_IV_MARKER = "__no_keystore__"
    }
}
