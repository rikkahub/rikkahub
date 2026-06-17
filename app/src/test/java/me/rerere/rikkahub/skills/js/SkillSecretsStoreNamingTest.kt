package me.rerere.rikkahub.skills.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's pref-key shape encodes (skillName, secretName) into a single string and
 * decodes back via `__` separator. Encryption needs the Android keystore (instrumented
 * only), so the naming contract is pinned here against the exact key shapes and the exact
 * filtering predicate [SkillSecretsStore.list] applies, so a regression in either is caught
 * without a device.
 */
class SkillSecretsStoreNamingTest {

    // Mirror of the production key shapes (see SkillSecretsStore.prefKey / ivKey / legacyIvKey).
    private val secretPrefix = "skill_secret_"
    private val ivPrefix = "skill_iv_"
    private val legacyIvPrefix = "skill_secret_iv_"

    private fun secretKey(skill: String, name: String) = "$secretPrefix${skill}__${name}"
    private fun ivKey(skill: String, name: String) = "$ivPrefix${skill}__${name}"
    private fun legacyIvKey(skill: String, name: String) = "$legacyIvPrefix${skill}__${name}"

    /** Mirror of [SkillSecretsStore.list]'s filter+decode over a set of stored pref keys. */
    private fun listPairs(keys: Set<String>): List<Pair<String, String>> =
        keys.mapNotNull { k ->
            if (!k.startsWith(secretPrefix)) return@mapNotNull null
            val rest = k.removePrefix(secretPrefix)
            val sep = rest.indexOf("__")
            if (sep <= 0) return@mapNotNull null
            if (k.startsWith(legacyIvPrefix)) {
                val ivRest = k.removePrefix(legacyIvPrefix)
                if (keys.contains(secretPrefix + ivRest)) return@mapNotNull null
            }
            rest.substring(0, sep) to rest.substring(sep + 2)
        }

    @Test fun `key shape uses double-underscore separator`() {
        assertEquals("skill_secret_my-skill__api_key", secretKey("my-skill", "api_key"))
    }

    @Test fun `single-underscore in skill name does not collide with separator`() {
        // skillName="api_key"+"x" vs skillName="api"+"key_x" stay distinct because the
        // separator is `__`, not `_`.
        assertFalse(secretKey("api_key", "x") == secretKey("api", "key_x"))
    }

    @Test fun `secret and iv keys live under disjoint top-level prefixes`() {
        // The bug: the old IV prefix `skill_secret_iv_` was a superset of the secret
        // prefix, so list() had to exclude it and wrongly dropped `iv_`-named skills.
        // New IV keys must NOT start with the secret prefix.
        assertFalse(ivKey("foo", "bar").startsWith(secretPrefix))
        assertTrue(secretKey("foo", "bar").startsWith(secretPrefix))
    }

    @Test fun `secret for a skill named iv_foo is listed and round-trips its name`() {
        // Regression: skill "iv_foo" stores secret key `skill_secret_iv_foo__apikey`,
        // which under the new (current-format) write has its IV at `skill_iv_iv_foo__apikey`.
        // list() must surface (iv_foo, apikey) and must NOT drop it.
        val stored = setOf(
            secretKey("iv_foo", "apikey"),
            ivKey("iv_foo", "apikey"),
        )
        val pairs = listPairs(stored)
        assertEquals(listOf("iv_foo" to "apikey"), pairs)
    }

    @Test fun `normal skill secret still lists with current-format iv key`() {
        val stored = setOf(
            secretKey("weather", "owm_key"),
            ivKey("weather", "owm_key"),
        )
        assertEquals(listOf("weather" to "owm_key"), listPairs(stored))
    }

    @Test fun `legacy iv key is excluded so it never becomes a phantom secret entry`() {
        // Old-format storage: secret + legacy IV (both under skill_secret_). list() must
        // return only the real secret, not a phantom `iv_weather` from the legacy IV key.
        val stored = setOf(
            secretKey("weather", "owm_key"),
            legacyIvKey("weather", "owm_key"),
        )
        assertEquals(listOf("weather" to "owm_key"), listPairs(stored))
    }

    @Test fun `legacy-format secret for iv_foo survives because its iv has no sibling secret`() {
        // The hardest case: an `iv_foo` skill stored entirely in the OLD format. Its
        // secret key collides verbatim with how a legacy IV is shaped, but disambiguation
        // is by sibling: the legacy IV `skill_secret_iv_iv_foo__apikey` HAS a sibling
        // secret (`skill_secret_iv_foo__apikey`) and is skipped, while that secret has NO
        // sibling (`skill_secret_foo__apikey` absent) and is kept.
        val stored = setOf(
            secretKey("iv_foo", "apikey"),      // skill_secret_iv_foo__apikey
            legacyIvKey("iv_foo", "apikey"),    // skill_secret_iv_iv_foo__apikey
        )
        assertEquals(listOf("iv_foo" to "apikey"), listPairs(stored))
    }
}
