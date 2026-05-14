package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDirectionResolverTest {

    @Test
    fun `plain english resolves to Ltr`() {
        assertEquals(TextDirection.Ltr, resolveTextDirection("Hello world"))
    }

    @Test
    fun `arabic resolves to Rtl`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("مرحبا بالعالم"))
    }

    @Test
    fun `persian resolves to Rtl`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("سلام دنیا"))
    }

    @Test
    fun `urdu resolves to Rtl`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("ہیلو دنیا"))
    }

    @Test
    fun `hebrew resolves to Rtl`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("שלום עולם"))
    }

    @Test
    fun `cjk resolves to Ltr`() {
        assertEquals(TextDirection.Ltr, resolveTextDirection("你好世界"))
    }

    @Test
    fun `leading whitespace is skipped to first strong char`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("   مرحبا"))
        assertEquals(TextDirection.Ltr, resolveTextDirection("   Hello"))
    }

    @Test
    fun `leading digits and punctuation are skipped to first strong char`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("123. مرحبا"))
        assertEquals(TextDirection.Ltr, resolveTextDirection("123. Hello"))
    }

    @Test
    fun `first strong char wins in mixed text`() {
        // English first -> Ltr even though Arabic follows
        assertEquals(TextDirection.Ltr, resolveTextDirection("Hello مرحبا"))
        // Arabic first -> Rtl even though English follows
        assertEquals(TextDirection.Rtl, resolveTextDirection("مرحبا Hello"))
    }

    @Test
    fun `no strong directional char resolves to Content`() {
        assertEquals(TextDirection.Content, resolveTextDirection(""))
        assertEquals(TextDirection.Content, resolveTextDirection("123 456"))
        assertEquals(TextDirection.Content, resolveTextDirection("   "))
        assertEquals(TextDirection.Content, resolveTextDirection("!@#$%^&*()"))
    }

    @Test
    fun `emoji-only text resolves to Content`() {
        assertEquals(TextDirection.Content, resolveTextDirection("😀👍"))
    }

    @Test
    fun `arabic presentation forms resolve to Rtl`() {
        // U+FB50 .. (Arabic Presentation Forms-A)
        assertEquals(TextDirection.Rtl, resolveTextDirection("ﭐﭑ"))
    }

    @Test
    fun `emoji prefix before arabic still resolves to Rtl`() {
        assertEquals(TextDirection.Rtl, resolveTextDirection("👍 مرحبا"))
    }
}
