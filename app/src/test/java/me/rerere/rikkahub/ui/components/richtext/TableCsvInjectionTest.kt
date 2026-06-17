package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for CSV formula-injection in table export. Markdown table cells
 * are model-emitted untrusted text; a field starting with `= + - @` (or a leading
 * tab/CR) is executed as a formula when the exported CSV is opened in a spreadsheet.
 * [escapeCsvField] must neutralize the lead trigger (prefix `'`) while preserving
 * RFC-4180 quoting for separators/quotes/newlines.
 */
class TableCsvInjectionTest {

    @Test
    fun `formula-leading fields are neutralized with a leading apostrophe`() {
        assertEquals("'=HYPERLINK(\"http://x\")", escapeCsvField("=HYPERLINK(\"http://x\")").trimQuotes())
        assertEquals("'+1+1", escapeCsvField("+1+1"))
        assertEquals("'-2+3", escapeCsvField("-2+3"))
        assertEquals("'@SUM(A1)", escapeCsvField("@SUM(A1)"))
    }

    @Test
    fun `leading tab, carriage return and line feed are neutralized`() {
        assertTrue(escapeCsvField("\t=cmd").startsWith("'\t"))
        // CR and LF trigger both neutralization and RFC-4180 quoting (OWASP lists 0x0A too).
        assertTrue(escapeCsvField("\r=cmd").contains("'"))
        val lf = escapeCsvField("\n=cmd")
        assertTrue("LF-leading cell must be neutralized with apostrophe", lf.contains("'"))
        assertTrue("LF-leading cell must be RFC-quoted", lf.startsWith("\""))
    }

    @Test
    fun `benign fields pass through unchanged`() {
        assertEquals("hello", escapeCsvField("hello"))
        assertEquals("123", escapeCsvField("123"))
        assertEquals("a formula = b later", escapeCsvField("a formula = b later"))
    }

    @Test
    fun `RFC-4180 quoting still applies and composes with neutralization`() {
        assertEquals("\"a,b\"", escapeCsvField("a,b"))
        assertEquals("\"he said \"\"hi\"\"\"", escapeCsvField("he said \"hi\""))
        // A cell that is both a formula trigger AND contains a separator: neutralize then quote.
        assertEquals("\"'=A,B\"", escapeCsvField("=A,B"))
    }

    @Test
    fun `full table export neutralizes a malicious cell`() {
        val csv = buildTableCsv(
            headerCells = listOf("name", "value"),
            rows = listOf(listOf("ok", "=1+1"), listOf("plain", "fine")),
        )
        assertTrue("malicious cell must be neutralized", csv.contains("'=1+1"))
        assertFalse("must not emit a live formula", csv.contains(",=1+1"))
        assertTrue(csv.contains("plain,fine"))
    }

    private fun String.trimQuotes(): String =
        if (startsWith("\"") && endsWith("\"")) substring(1, length - 1).replace("\"\"", "\"") else this
}
