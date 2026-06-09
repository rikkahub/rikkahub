package me.rerere.automation.act

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.automation.observe.UiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based tests for [SubmitClassifier] (#198 slice 11). The classifier is the only thing that
 * upgrades a plain CLICK to the dangerous `Sink.SUBMIT`, so its totality and conservatism are
 * safety-critical: every property is written so a trivial `false`-returning classifier (or a
 * whole-word-only one) FAILS, while the real over-broad substring classifier passes.
 *
 * Conservatism contract under test (design I-act-5): match ANY commit keyword as a case-insensitive
 * SUBSTRING of EITHER `text` OR `semanticKey`; null fields never match and never throw; the same input
 * is always classified the same way. Over-broad on purpose — a false positive costs one harmless
 * confirm, a false negative an unconfirmed pay.
 */
class SubmitClassifierPropertyTest {

    /** Benign single-token labels that must STAY non-submit (the slice-10 happy-path "OK" tap, etc.). */
    private val BENIGN = listOf("OK", "Cancel", "Back", "Close", "Edit", "Search", "Settings", "Menu", "Help")

    /** Random padding that is guaranteed NOT to introduce a commit keyword as a substring. */
    private fun benignPadding(): Arb<String> =
        Arb.string(0..8, Codepoint.alphanumeric()).filter { pad ->
            val lower = pad.lowercase()
            SubmitClassifier.COMMIT_KEYWORDS.none { lower.contains(it) }
        }

    // ---- every keyword, embedded with benign padding + random case, classifies as submit (via TEXT) ----
    // A trivial `false` classifier FAILS; a whole-word-only classifier FAILS on the padded/substring cases.
    @Test
    fun `every commit keyword in the text classifies as submit-class`() {
        runBlocking {
            checkAll(
                400,
                Arb.element(SubmitClassifier.COMMIT_KEYWORDS),
                benignPadding(),
                benignPadding(),
                Arb.element(true, false),
            ) { keyword, prefix, suffix, upper ->
                val raw = prefix + keyword + suffix
                val cased = if (upper) raw.uppercase() else raw
                assertTrue(
                    "a target whose text contains the commit keyword \"$keyword\" must be submit-class (text=\"$cased\")",
                    SubmitClassifier.isSubmitClass(text = cased, semanticKey = null),
                )
                // And via a full UiTarget (the production entry point) with the keyword on text.
                assertTrue(
                    SubmitClassifier.isSubmitClass(target(text = cased, semanticKey = null)),
                )
            }
        }
    }

    // ---- every keyword on the semanticKey (text null) also classifies as submit-class ----
    @Test
    fun `every commit keyword in the semanticKey classifies as submit-class`() {
        runBlocking {
            checkAll(
                400,
                Arb.element(SubmitClassifier.COMMIT_KEYWORDS),
                benignPadding(),
                benignPadding(),
                Arb.element(true, false),
            ) { keyword, prefix, suffix, upper ->
                val raw = prefix + keyword + suffix
                val cased = if (upper) raw.uppercase() else raw
                assertTrue(
                    "a target whose semanticKey contains the commit keyword \"$keyword\" must be submit-class (key=\"$cased\")",
                    SubmitClassifier.isSubmitClass(text = null, semanticKey = cased),
                )
                assertTrue(SubmitClassifier.isSubmitClass(target(text = null, semanticKey = cased)))
            }
        }
    }

    // ---- a clearly-benign label (no keyword substring) classifies as NOT submit, on BOTH fields ----
    // A classifier that returns true too eagerly (e.g. always-true) FAILS this; the substring rule passes.
    @Test
    fun `a benign label is never submit-class`() {
        runBlocking {
            checkAll(400, Arb.element(BENIGN), benignPadding()) { benign, pad ->
                // benign label alone (the literal {"OK","Cancel",...}).
                assertFalse(
                    "benign label \"$benign\" must not be submit-class",
                    SubmitClassifier.isSubmitClass(text = benign, semanticKey = null),
                )
                // a random benign-padded label that provably contains no commit keyword, on either field.
                assertFalse(
                    "padded benign label \"$pad\" must not be submit-class (text)",
                    SubmitClassifier.isSubmitClass(text = pad, semanticKey = null),
                )
                assertFalse(
                    "padded benign label \"$pad\" must not be submit-class (key)",
                    SubmitClassifier.isSubmitClass(text = null, semanticKey = pad),
                )
            }
        }
    }

    // ---- TOTALITY: the classifier returns a Boolean for every nullable (text, semanticKey) pair and
    // NEVER throws. null/null is explicitly false (no fields ⇒ no match). ----
    @Test
    fun `classifier is total over nullable fields`() {
        // null on both ⇒ false (and no throw).
        assertEquals(false, SubmitClassifier.isSubmitClass(text = null, semanticKey = null))
        assertEquals(false, SubmitClassifier.isSubmitClass(target(text = null, semanticKey = null)))

        // Arb<String?> mixes random strings with explicit nulls, so every nullable combination
        // (null/null, null/str, str/null, str/str) is exercised; the contract here is just "returns a
        // Boolean, never throws" (value correctness is pinned by the other properties).
        val nullableString: Arb<String?> = Arb.string(0..16).orNull(0.3)
        runBlocking {
            checkAll(400, nullableString, nullableString) { t, k ->
                SubmitClassifier.isSubmitClass(text = t, semanticKey = k)
                SubmitClassifier.isSubmitClass(target(text = t, semanticKey = k))
            }
        }
    }

    // ---- DETERMINISM: the same target ⇒ the same result twice (pure, no hidden state) ----
    @Test
    fun `classifier is deterministic`() {
        runBlocking {
            checkAll(400, Arb.string(0..16), Arb.string(0..16)) { t, k ->
                val first = SubmitClassifier.isSubmitClass(text = t, semanticKey = k)
                val second = SubmitClassifier.isSubmitClass(text = t, semanticKey = k)
                assertEquals("same input must classify identically", first, second)
            }
        }
    }

    // ---- a SUBSTRING (not whole-word) match trips: "PayPalSend" contains "send" / "pay" ⇒ submit.
    // This is the headline conservatism property: a whole-word classifier would miss this and let an
    // unconfirmed commit through. ----
    @Test
    fun `a substring keyword (not whole word) still classifies as submit-class`() {
        assertTrue(SubmitClassifier.isSubmitClass(text = "PayPalSend", semanticKey = null))
        assertTrue(SubmitClassifier.isSubmitClass(text = "btn_submit_form", semanticKey = null))
        assertTrue(SubmitClassifier.isSubmitClass(text = null, semanticKey = "checkoutNow"))
    }

    // ---- LOCALIZED labels (no English fallback) classify as submit-class. An English-only classifier
    // FAILS every one of these — and a non-English unconfirmed pay is the catastrophic case (#198 §11).
    // Each value is a real pay/commit label with NO Latin commit word, so it only passes if the
    // localized keyword is actually recognized (the prior test's `立即购买 (purchase)` cheated via the
    // English parenthetical). ----
    @Test
    fun `localized commit labels classify as submit-class`() {
        val localized = listOf(
            "确认支付",   // zh-Hans: confirm payment
            "立即购买",   // zh-Hans: buy now
            "下单",       // zh-Hans: place order
            "確認支付",   // zh-Hant: confirm payment
            "決済する",   // ja: settle / pay
            "購入手続き", // ja: purchase procedure
            "결제하기",   // ko: pay
            "구매",       // ko: purchase
            "оплатить",   // ru: pay
            "купить",     // ru: buy
            "Jetzt kaufen", // de: buy now
        )
        for (label in localized) {
            assertTrue(
                "localized commit label \"$label\" must be submit-class",
                SubmitClassifier.isSubmitClass(text = label, semanticKey = null),
            )
            assertTrue(SubmitClassifier.isSubmitClass(target(text = label, semanticKey = null)))
        }
    }

    // ---- an ICON-ONLY commit button (no visible text, no contentDescription) is still submit-class
    // via its raw view id — resource ids are developer-named (English) even in a localized app, so
    // `…:id/pay_button` trips `pay`. Without the viewId input this is a textless false-negative that
    // taps an unconfirmed pay. A classifier that ignores viewId FAILS this. ----
    @Test
    fun `an icon-only button with a commit view id classifies as submit-class`() {
        assertTrue(
            "an icon-only pay button must classify via its view id",
            SubmitClassifier.isSubmitClass(text = null, semanticKey = null, viewId = "com.shop.app:id/pay_button"),
        )
        assertTrue(
            SubmitClassifier.isSubmitClass(target(text = null, semanticKey = null, viewId = "com.shop.app:id/btn_checkout")),
        )
        // A benign view id (no commit keyword) stays non-submit even with null text/key.
        assertFalse(
            SubmitClassifier.isSubmitClass(text = null, semanticKey = null, viewId = "com.shop.app:id/back_arrow"),
        )
    }

    private fun target(text: String?, semanticKey: String?, viewId: String? = null): UiTarget =
        UiTarget(tid = 0, role = "Button", text = text, semanticKey = semanticKey, viewId = viewId)
}
