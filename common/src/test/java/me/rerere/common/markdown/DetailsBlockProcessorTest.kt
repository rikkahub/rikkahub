package me.rerere.common.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsBlockProcessorTest {
    @Test
    fun `extractDetailsBlocks keeps multiline details together`() {
        val sample = """
            <details>
            <summary>title</summary>
            <p>
            content
            
            **item**
            </p>
            </details>
        """.trimIndent()

        val extraction = extractDetailsBlocks(sample)

        assertEquals(1, extraction.blocks.size)
        val placeholder = extraction.blocks.keys.single()
        assertEquals(sample, extraction.blocks.getValue(placeholder))
        assertTrue(extraction.content.contains(placeholder))
        assertTrue(!extraction.content.contains("**item**"))
    }

    @Test
    fun `extractDetailsBlocks ignores details tags inside tilde fenced code blocks`() {
        val sample = """
            ~~~markdown
            <details>
            <summary>code sample</summary>
            still code
            </details>
            ~~~
        """.trimIndent()

        val extraction = extractDetailsBlocks(sample)

        assertEquals(0, extraction.blocks.size)
        assertEquals(sample, extraction.content)
    }

    @Test
    fun `extractDetailsBlocks ignores details tags inside multi backtick code spans`() {
        val sample = "``<details><summary>code sample</summary>still code</details>``"

        val extraction = extractDetailsBlocks(sample)

        assertEquals(0, extraction.blocks.size)
        assertEquals(sample, extraction.content)
    }

    @Test
    fun `extractDetailsBlocks preserves list indentation for placeholder`() {
        val sample = """
            - item
              <details>
              <summary>more</summary>
              body
              </details>
            - next
        """.trimIndent()

        val extraction = extractDetailsBlocks(sample)

        assertEquals(1, extraction.blocks.size)
        val placeholder = extraction.blocks.keys.single()
        assertTrue(extraction.content.contains("\n  $placeholder\n"))
    }

    @Test
    fun `prepareDetailsBodyForMarkdown unwraps div body`() {
        val parsed = parseDetailsBlock(
            """
            <details>
            <summary>title</summary>
            <div>
            content
            - item
            </div>
            </details>
            """.trimIndent()
        )!!

        assertEquals(
            """
            content
            - item
            """.trimIndent(),
            prepareDetailsBodyForMarkdown(parsed.bodyRaw)
        )
    }

    @Test
    fun `prepareDetailsBodyForMarkdown unwraps paragraph body without losing markdown`() {
        val parsed = parseDetailsBlock(
            """
            <details>
            <summary>title</summary>
            <p>
            content
            **item**
            </p>
            </details>
            """.trimIndent()
        )!!

        assertEquals(
            """
            content
            **item**
            """.trimIndent(),
            prepareDetailsBodyForMarkdown(parsed.bodyRaw)
        )
    }

    @Test
    fun `parseDetailsBlock does not treat open in attribute values as open attribute`() {
        val parsed = parseDetailsBlock(
            """
            <details class="open panel">
            <summary>title</summary>
            content
            </details>
            """.trimIndent()
        )!!

        assertEquals(false, parsed.openByDefault)
    }

    @Test
    fun `parseDetailsBlock ignores open token inside quoted attribute text`() {
        val parsed = parseDetailsBlock(
            """
            <details title="starts open by default">
            <summary>title</summary>
            content
            </details>
            """.trimIndent()
        )!!

        assertEquals(false, parsed.openByDefault)
    }

    @Test
    fun `parseDetailsBlock keeps real open attribute`() {
        val parsed = parseDetailsBlock(
            """
            <details class="panel" open>
            <summary>title</summary>
            content
            </details>
            """.trimIndent()
        )!!

        assertEquals(true, parsed.openByDefault)
    }

    @Test
    fun `prepareDetailsBodyForMarkdown keeps blank lines inside paragraph body`() {
        val parsed = parseDetailsBlock(
            """
            <details>
            <summary>title</summary>
            <p>
            content
            
            **item**
            </p>
            </details>
            """.trimIndent()
        )!!

        assertEquals(
            """
            content
            
            **item**
            """.trimIndent(),
            prepareDetailsBodyForMarkdown(parsed.bodyRaw)
        )
    }

    @Test
    fun `prepareDetailsBodyForMarkdown keeps direct markdown lines`() {
        val parsed = parseDetailsBlock(
            """
            <details>
            <summary>title</summary>
            *content*
            **item**
            </details>
            """.trimIndent()
        )!!

        assertEquals(
            """
            *content*
            **item**
            """.trimIndent(),
            prepareDetailsBodyForMarkdown(parsed.bodyRaw)
        )
    }

    @Test
    fun `prepareDetailsBodyForMarkdown preserves nested details blocks`() {
        val parsed = parseDetailsBlock(
            """
            <details>
            <summary>outer</summary>
            <p>
            content
            **item**
            </p>
            <details>
            <summary>inner</summary>
            <p>nested</p>
            </details>
            </details>
            """.trimIndent()
        )!!

        val body = prepareDetailsBodyForMarkdown(parsed.bodyRaw)

        assertTrue(body.contains("content"))
        assertTrue(body.contains("**item**"))
        assertTrue(body.contains("<details>"))
        assertTrue(body.contains("<summary>inner</summary>"))
        assertTrue(body.contains("nested"))
    }
}
