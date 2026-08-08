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
    fun glsl() = HljsFixtures.assertLanguageMatches("glsl")

    @Test
    fun yaml() = HljsFixtures.assertLanguageMatches("yaml")

    @Test
    fun bash() = HljsFixtures.assertLanguageMatches("bash")

    @Test
    fun dockerfile() = HljsFixtures.assertLanguageMatches("dockerfile")

    @Test
    fun javascript() = HljsFixtures.assertLanguageMatches("javascript")

    @Test
    fun typescript() = HljsFixtures.assertLanguageMatches("typescript")

    @Test
    fun xml() = HljsFixtures.assertLanguageMatches("xml")

    @Test
    fun css() = HljsFixtures.assertLanguageMatches("css")

    @Test
    fun dart() = HljsFixtures.assertLanguageMatches("dart")

    @Test
    fun java() = HljsFixtures.assertLanguageMatches("java")

    @Test
    fun kotlin() = HljsFixtures.assertLanguageMatches("kotlin")

    @Test
    fun latex() = HljsFixtures.assertLanguageMatches("latex")

    @Test
    fun lua() = HljsFixtures.assertLanguageMatches("lua")

    @Test
    fun powershell() = HljsFixtures.assertLanguageMatches("powershell")

    @Test
    fun properties() = HljsFixtures.assertLanguageMatches("properties")

    @Test
    fun python() = HljsFixtures.assertLanguageMatches("python")

    @Test
    fun c() = HljsFixtures.assertLanguageMatches("c")

    @Test
    fun cpp() = HljsFixtures.assertLanguageMatches("cpp")

    @Test
    fun csharp() = HljsFixtures.assertLanguageMatches("csharp")

    @Test
    fun sql() = HljsFixtures.assertLanguageMatches("sql")

    @Test
    fun diff() = HljsFixtures.assertLanguageMatches("diff")

    @Test
    fun markdown() = HljsFixtures.assertLanguageMatches("markdown")

    @Test
    fun rust() = HljsFixtures.assertLanguageMatches("rust")

    @Test
    fun ruby() = HljsFixtures.assertLanguageMatches("ruby")

    @Test
    fun php() = HljsFixtures.assertLanguageMatches("php")

    @Test
    fun swift() = HljsFixtures.assertLanguageMatches("swift")
}
