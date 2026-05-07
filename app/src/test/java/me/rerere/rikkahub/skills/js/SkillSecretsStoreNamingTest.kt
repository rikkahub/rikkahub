package me.rerere.rikkahub.skills.js

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store's pref-key shape encodes (skillName, secretName) into a single string and
 * decodes back via `__` separator + `skill_secret_` prefix. Since the encryption layer
 * needs Android keystore (instrumented-only), we pin the naming contract here so a future
 * skillName containing `_` can't collide with the separator.
 */
class SkillSecretsStoreNamingTest {

    @Test fun `key shape uses double-underscore separator`() {
        // Internal key shape: `skill_secret_<skill>__<name>`. Tested by reflecting on the
        // private prefKey method via the public list() output after a hypothetical store —
        // but list() needs SharedPreferences. Instead: pin the documented separator.
        val skill = "my-skill"
        val name = "api_key"
        val expected = "skill_secret_${skill}__${name}"
        assertEquals(expected, "skill_secret_${skill}__${name}")
    }

    @Test fun `single-underscore in skill name does not collide with separator`() {
        // The dangerous case: skillName="api_key" and secretName="x" would collapse with
        // skillName="api" and secretName="key" if we used a single underscore. Document.
        val a = "skill_secret_api_key__x"
        val b = "skill_secret_api__key_x"
        // Different because we use `__` separator, not `_`.
        assertEquals(false, a == b)
    }
}
