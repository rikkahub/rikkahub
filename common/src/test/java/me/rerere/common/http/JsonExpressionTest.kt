package me.rerere.common.http

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class JsonExpressionTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // --- ISSUE 1: x / X must be usable as field identifiers, not a multiply alias ---

    @Test
    fun `bare x is a field path not a star operator`() {
        // Unfixed: `x` lexes to STAR -> parsePrimary sees STAR -> ParseException.
        assertEquals("42", evaluateJsonExpr("x", obj("""{"x":42}""")))
    }

    @Test
    fun `nested X is a field path`() {
        assertEquals("7", evaluateJsonExpr("data.X", obj("""{"data":{"X":7}}""")))
    }

    @Test
    fun `x and X are valid identifiers`() {
        assertTrue(isJsonExprValid("x"))
        assertTrue(isJsonExprValid("X"))
        assertTrue(isJsonExprValid("data.x"))
    }

    @Test
    fun `star still multiplies`() {
        assertEquals("6", evaluateJsonExpr("2 * 3", obj("""{}""")))
    }

    // --- ISSUE 2: fractional array index must be rejected, not truncated ---

    @Test
    fun `fractional array index is rejected`() {
        // Unfixed: "1.9".toDouble().toInt() == 1 -> silently returns items[1].
        assertFalse(isJsonExprValid("items[1.9]"))
    }

    @Test
    fun `fractional array index throws on evaluate`() {
        try {
            evaluateJsonExpr("items[1.9]", obj("""{"items":[10,20,30]}"""))
            throw AssertionError("expected fractional index to throw")
        } catch (e: AssertionError) {
            throw e
        } catch (_: Exception) {
            // expected: ParseException surfaces as a RuntimeException
        }
    }

    @Test
    fun `integer array index still resolves`() {
        assertEquals("20", evaluateJsonExpr("items[1]", obj("""{"items":[10,20,30]}""")))
    }

    @Test
    fun `out of range integer index resolves to empty string`() {
        assertEquals("", evaluateJsonExpr("items[5]", obj("""{"items":[10,20,30]}""")))
    }

    // --- ISSUE 3: precision must not be truncated to 2 decimals ---

    @Test
    fun `decimal value keeps full precision`() {
        // Unfixed: "%.2f".format(3.14159) -> "3.14".
        assertEquals("3.14159", evaluateJsonExpr("v", obj("""{"v":3.14159}""")))
    }

    @Test
    fun `high precision value round trips without truncation`() {
        assertEquals("1.23456789", evaluateJsonExpr("v", obj("""{"v":1.23456789}""")))
    }

    // --- ISSUE 3b: locale-independent number handling ---

    @Test
    fun `decimal value is locale independent`() {
        // Unfixed: under a decimal-comma locale, "%.2f".format(1.23) -> "1,23",
        // then "1,23".toDouble() throws NumberFormatException.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.23", evaluateJsonExpr("v", obj("""{"v":1.23}""")))
        } finally {
            Locale.setDefault(saved)
        }
    }

    // --- PROPERTY: boundary identifiers ---

    @Test
    fun `single-char and underscore identifiers resolve`() {
        runBlocking {
            checkAll(Arb.element("x", "X", "_x", "x1", "_", "y")) { name ->
                assertTrue(isJsonExprValid(name))
                assertEquals("9", evaluateJsonExpr(name, obj("""{"$name":9}""")))
            }
        }
    }

    // --- PROPERTY: parenthesization invariance ---

    @Test
    fun `wrapping an expression in parens does not change result`() {
        runBlocking {
            checkAll(Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50)) { a, b, c ->
                val expr = "$a + $b * $c"
                assertEquals(
                    evaluateJsonExpr(expr, obj("""{}""")),
                    evaluateJsonExpr("($expr)", obj("""{}"""))
                )
            }
        }
    }

    // --- PROPERTY: whitespace invariance ---

    @Test
    fun `whitespace around operators does not change result`() {
        runBlocking {
            checkAll(Arb.int(-50..50), Arb.int(-50..50)) { a, b ->
                val tight = "$a+$b"
                val loose = "$a   +   $b"
                assertEquals(
                    evaluateJsonExpr(tight, obj("""{}""")),
                    evaluateJsonExpr(loose, obj("""{}"""))
                )
            }
        }
    }

    // --- PROPERTY: locale invariance over arbitrary doubles ---

    @Test
    fun `formatted number is identical across locales`() {
        runBlocking {
            checkAll(Arb.double(-1000.0..1000.0)) { d ->
                if (d.isNaN() || d.isInfinite()) return@checkAll
                val root = obj("""{"v":$d}""")
                val saved = Locale.getDefault()
                val results = mutableListOf<String>()
                try {
                    for (loc in listOf(Locale.US, Locale.GERMANY, Locale.ROOT)) {
                        Locale.setDefault(loc)
                        results.add(evaluateJsonExpr("v", root))
                    }
                } finally {
                    Locale.setDefault(saved)
                }
                assertTrue(results.all { it == results[0] })
            }
        }
    }

    // --- PROPERTY: arithmetic laws over a finite safe int range ---

    @Test
    fun `addition and multiplication are commutative and associative`() {
        runBlocking {
            checkAll(Arb.int(-30..30), Arb.int(-30..30), Arb.int(-30..30)) { a, b, c ->
                fun e(s: String) = evaluateJsonExpr(s, obj("""{}"""))
                assertEquals(e("$a + $b"), e("$b + $a"))
                assertEquals(e("$a * $b"), e("$b * $a"))
                assertEquals(e("($a + $b) + $c"), e("$a + ($b + $c)"))
            }
        }
    }
}
