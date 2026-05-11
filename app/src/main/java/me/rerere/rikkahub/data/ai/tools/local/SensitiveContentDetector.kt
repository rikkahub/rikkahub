package me.rerere.rikkahub.data.ai.tools.local

/**
 * Heuristic detector for content the LLM should not casually echo back to the user
 * or paste into a URL bar. Strictly best-effort pattern matching — NOT a security
 * boundary. The intent is twofold:
 *
 *  - clipboard_tool(read): when the user has just copied a token / password / card
 *    number, surface a `sensitive_content_detected` flag so the LLM treats the
 *    payload with care (don't quote it, don't include it in summaries).
 *  - browser_open: when the URL's query string carries something that looks like a
 *    base64-encoded blob or a credit-card-shaped digit run, surface a `warning` so
 *    the LLM can verify the destination isn't an exfiltration endpoint before
 *    handing the user a "navigate?" prompt.
 *
 * Patterns are deliberately conservative: a few false negatives are fine (we are
 * not a DLP system), but false positives that fire on every other clipboard read
 * would teach the model to ignore the warning.
 */
internal object SensitiveContentDetector {

    /** JWT — three base64url segments separated by '.'. Almost zero false positives. */
    private val JWT = Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")

    /** Common API-key / token shapes (Stripe, GitHub, Slack, AWS, generic `sk-…`). */
    private val API_KEY_SHAPED = Regex(
        """\b(?:sk-[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\-]{20,})\b"""
    )

    /** A long opaque base64-ish blob (>=40 chars). Catches generic encoded payloads. */
    private val LONG_BASE64 = Regex("""[A-Za-z0-9+/=_\-]{40,}""")

    /** 13–19 contiguous digits, optionally separated by spaces or dashes — credit-card-shaped. */
    private val CARD_LIKE = Regex("""\b(?:\d[ \-]?){13,19}\b""")

    /** Standard email shape — used together with a colon-separated secret to catch creds. */
    private val EMAIL_PASS = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}:\S{6,}""")

    enum class Category { JWT, API_KEY, CREDIT_CARD, EMAIL_PASSWORD, LONG_OPAQUE_BLOB }

    /**
     * Scan [text] and return the categories matched. Caller decides what to do with
     * the list — typically: empty → no warning; non-empty → add a warning field to
     * the tool result envelope.
     */
    fun scan(text: String?): List<Category> {
        if (text.isNullOrEmpty()) return emptyList()
        val hits = mutableListOf<Category>()
        if (JWT.containsMatchIn(text)) hits += Category.JWT
        if (API_KEY_SHAPED.containsMatchIn(text)) hits += Category.API_KEY
        if (EMAIL_PASS.containsMatchIn(text)) hits += Category.EMAIL_PASSWORD
        // CARD_LIKE on its own would fire on long order numbers, etc., so we only
        // count it when the digit run actually passes the Luhn checksum.
        CARD_LIKE.findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhnValid(digits)) {
                hits += Category.CREDIT_CARD
                return@forEach
            }
        }
        // Run LONG_BASE64 only if none of the more-specific shapes hit, otherwise
        // every JWT also fires LONG_OPAQUE_BLOB and the warning becomes noise.
        if (hits.isEmpty() && LONG_BASE64.containsMatchIn(text)) {
            hits += Category.LONG_OPAQUE_BLOB
        }
        return hits.distinct()
    }

    /**
     * Same scan, but only inspect the query string of [url]. Returns the matched
     * categories. Used by browser_open to avoid flagging URLs whose PATH segments
     * happen to contain long opaque tokens (e.g. CDN asset hashes are not exfil).
     */
    fun scanUrlQuery(url: String?): List<Category> {
        if (url.isNullOrEmpty()) return emptyList()
        val q = url.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
        if (q.isEmpty()) return emptyList()
        return scan(q)
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var n = digits[i].digitToIntOrNull() ?: return false
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
