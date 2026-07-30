package me.rerere.highlight.languages

import me.rerere.highlight.HljsFixtures
import org.junit.Test

/** Every bundled grammar is checked against the token stream `highlight.js` produces. */
class LanguageFixtureTest {
    @Test
    fun json() = HljsFixtures.assertLanguageMatches("json")

    @Test
    fun toml() = HljsFixtures.assertLanguageMatches("toml")

    @Test
    fun cmake() = HljsFixtures.assertLanguageMatches("cmake")

    @Test
    fun go() = HljsFixtures.assertLanguageMatches("go")

    @Test
    fun yaml() = HljsFixtures.assertLanguageMatches("yaml")

    @Test
    fun bash() = HljsFixtures.assertLanguageMatches("bash")

    @Test
    fun dockerfile() = HljsFixtures.assertLanguageMatches("dockerfile")
}
