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
        // Read the IV from the current namespace, falling back to the legacy
        // `skill_secret_iv_` key written before the namespace split so secrets
        // stored by older builds still decrypt.
        val iv = prefs.getString(ivKey(skillName, secretName), null)
            ?: prefs.getString(legacyIvKey(skillName, secretName), null)
            ?: return null
        return decrypt(encrypted, iv)
    }

    fun remove(skillName: String, secretName: String) {
        prefs.edit {
            remove(prefKey(skillName, secretName))
            remove(ivKey(skillName, secretName))
            remove(legacyIvKey(skillName, secretName))
        }
    }

    /** All (skillName, secretName) pairs the user currently has stored. */
    fun list(): List<Pair<String, String>> {
        // New writes put IVs under their own `skill_iv_` namespace (disjoint from
        // `skill_secret_`), so a secret never shares a prefix with an IV.
        //
        // The one hard case is legacy data: legacy IVs were written under
        // `skill_secret_iv_<skill>__<name>`, which is byte-for-byte identical to the
        // *secret* key of a skill literally named `iv_<skill>`. They are told apart by
        // the fact that a legacy IV always has a sibling secret key
        // `skill_secret_<skill>__<name>` present; a real `iv_`-named secret does not.
        // So a `skill_secret_iv_*` key is treated as a legacy IV (and skipped) only
        // when that sibling exists, which both keeps `iv_`-named skills' secrets and
        // avoids surfacing legacy IVs as phantom secret entries.
        val allKeys = prefs.all.keys
        return allKeys.mapNotNull { k ->
            if (!k.startsWith(SECRET_PREFIX)) return@mapNotNull null
            val rest = k.removePrefix(SECRET_PREFIX)
            val sep = rest.indexOf("__")
            if (sep <= 0) return@mapNotNull null
            if (k.startsWith(LEGACY_IV_PREFIX)) {
                // Candidate legacy IV: skip it iff the secret it would belong to exists.
                val ivRest = k.removePrefix(LEGACY_IV_PREFIX)
                if (allKeys.contains(SECRET_PREFIX + ivRest)) return@mapNotNull null
            }
            rest.substring(0, sep) to rest.substring(sep + 2)
        }
    }

    /** Remove every secret stored for a skill, used when the user uninstalls it. */
    fun removeAllForSkill(skillName: String) {
        // Match the secret key, the current IV key, and the legacy IV key so an
        // uninstall written by an older build leaves nothing behind.
        val toRemove = prefs.all.keys.filter {
            it.startsWith("$SECRET_PREFIX$skillName" + "__") ||
                it.startsWith("$IV_PREFIX$skillName" + "__") ||
                it.startsWith("$LEGACY_IV_PREFIX$skillName" + "__")
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

    private fun prefKey(skill: String, name: String): String = "$SECRET_PREFIX${skill}__${name}"
    private fun ivKey(skill: String, name: String): String = "$IV_PREFIX${skill}__${name}"

    // Legacy IV key shape (`skill_secret_iv_<skill>__<name>`) written before the IV
    // namespace was split out. Kept for backward-read so secrets stored by older
    // builds still decrypt; new writes use [ivKey].
    private fun legacyIvKey(skill: String, name: String): String =
        "$LEGACY_IV_PREFIX${skill}__${name}"

    companion object {
        private const val PREFS_NAME = "skill_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rikkahub_skill_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FALLBACK_IV_MARKER = "__no_keystore__"

        // Secrets and IVs live under disjoint top-level prefixes. The old scheme put
        // IVs under `skill_secret_iv_`, a superset of the secret prefix, which forced
        // list() into an exclusion filter that wrongly dropped any skill whose name
        // started with `iv_`. The disjoint prefixes make list() a plain prefix match.
        private const val SECRET_PREFIX = "skill_secret_"
        private const val IV_PREFIX = "skill_iv_"
        private const val LEGACY_IV_PREFIX = "skill_secret_iv_"
    }
}
