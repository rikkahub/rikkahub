package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pins the Locale construction equivalence the deprecated-constructor migration
 * relies on: `Locale.forLanguageTag(tag)` must produce locales `equals()`-equal
 * to what the removed `Locale(lang[, country])` constructor produced, because
 * LanguageSelectionDialog (ChatMessageTranslation.kt) and TranslatorPage match
 * the selected locale against these literals with `when (locale)` equality.
 * Also pins the legacy ISO 639 code normalization (iw/in/ji -> he/id/yi) on the
 * build JDK so a toolchain change that breaks the mapping fails loudly here.
 */
class LocaleTagMappingTest {

    @Suppress("DEPRECATION") // intentionally uses the deprecated constructor to pin migration equivalence
    @Test
    fun `forLanguageTag yields locales equal to deprecated constructor for migrated literals`() {
        assertEquals(Locale("es", "ES"), Locale.forLanguageTag("es-ES"))
        assertEquals(Locale("id"), Locale.forLanguageTag("id"))
    }

    @Suppress("DEPRECATION") // intentionally uses the deprecated constructor to pin legacy-code equivalence
    @Test
    fun `forLanguageTag normalizes legacy codes identically to deprecated constructor`() {
        assertEquals(Locale("iw"), Locale.forLanguageTag("iw"))
        assertEquals(Locale("in"), Locale.forLanguageTag("in"))
        assertEquals(Locale("ji"), Locale.forLanguageTag("ji"))
    }

    @Test
    fun `legacy codes iw in ji map to modern tags he id yi`() {
        assertEquals("he", Locale.forLanguageTag("iw").toLanguageTag())
        assertEquals("id", Locale.forLanguageTag("in").toLanguageTag())
        assertEquals("yi", Locale.forLanguageTag("ji").toLanguageTag())
    }

    @Test
    fun `migrated literals round-trip through toLanguageTag`() {
        val spanish = Locale.forLanguageTag("es-ES")
        assertEquals("es", spanish.language)
        assertEquals("ES", spanish.country)
        assertEquals("es-ES", spanish.toLanguageTag())
        assertEquals("id", Locale.forLanguageTag("id").toLanguageTag())
    }

    @Test
    fun `translation UI sources do not call the deprecated Locale constructor`() {
        val files = listOf(
            "src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTranslation.kt",
            "src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorPage.kt",
        ).map { rel ->
            val moduleRelative = File(rel)
            if (moduleRelative.isFile) moduleRelative else File("app/$rel")
        }
        files.forEach { file ->
            assertTrue("Could not locate ${file.path}; test would otherwise pass vacuously", file.isFile)
        }

        val constructorCall = Regex("""\bLocale\s*\(""")
        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (constructorCall.containsMatchIn(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
            }
        }

        assertTrue(
            "Deprecated Locale(lang[, country]) constructor in translation UI " +
                "(use Locale.forLanguageTag, see class doc):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
