package me.rerere.rikkahub.data.ai.memory

import java.security.MessageDigest

/**
 * Stable content hash for a memory's embedded text — one half of the vector freshness key
 * (issue #210 §4). A stored vector is only usable for ranking when this hash of the memory's CURRENT
 * content matches the hash recorded when the vector was produced; otherwise the content was edited
 * after embedding and the vector is stale.
 *
 * SHA-256 hex: deterministic (same input ⇒ same output across runs/processes — the property P9/P11
 * depend on) and collision-safe enough for a freshness gate. Pure JVM (`MessageDigest`) so it is
 * unit-testable headlessly without Android.
 */
fun memoryContentHash(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
