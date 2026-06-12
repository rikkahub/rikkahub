package me.rerere.common.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins the Accept-Language header output of [AcceptLanguageBuilder.build] so
 * the deprecated `Configuration.locale` accessor migration (and any future
 * locale-source change) is provably behavior-preserving: the header text for a
 * given locale preference list must not change.
 */
class AcceptLangTest {

    @Test
    fun `build expands regional tags and assigns descending q values`() {
        val header = AcceptLanguageBuilder.withLocales(
            listOf(Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("en-US"))
        ).build()
        assertEquals("zh-CN, zh;q=0.9, en-US;q=0.8, en;q=0.7", header)
    }

    @Test
    fun `build emits a bare language without q or generic expansion`() {
        assertEquals("en", AcceptLanguageBuilder.withLocales(listOf(Locale.ENGLISH)).build())
    }

    @Test
    fun `build deduplicates a generic tag already in the preference list`() {
        val header = AcceptLanguageBuilder.withLocales(
            listOf(Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("zh"))
        ).build()
        assertEquals("zh-CN, zh;q=0.9", header)
    }

    @Test
    fun `build truncates to maxLanguages after deduplication`() {
        val header = AcceptLanguageBuilder.withLocales(
            listOf(Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("en-US")),
            AcceptLanguageBuilder.Options(maxLanguages = 2)
        ).build()
        assertEquals("zh-CN, zh;q=0.9", header)
    }

    @Test
    fun `build normalizes the legacy Indonesian code to the modern tag`() {
        assertEquals("id", AcceptLanguageBuilder.withLocales(listOf(Locale.forLanguageTag("in"))).build())
    }
}
