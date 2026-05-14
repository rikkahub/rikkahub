package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of an NFC reader-mode session hosted in [ToolHostActivity].
 *
 * [records] is the JSON-string form of the LLM-facing record list (read) or echoed back
 * on a successful write. [payloadB64] / [tnf] / [typeB64] only populate for raw records;
 * the activity does the NDEF decode and hands back the already-shaped JSON so the tool
 * factory stays thin.
 */
sealed class NfcResult {
    /** Tag read succeeded. [recordsJson] is a JSON array string; [tagIdHex] the tag UID. */
    data class ReadOk(val recordsJson: String, val tagIdHex: String) : NfcResult()
    /** Tag write succeeded. */
    data class WriteOk(val tagIdHex: String) : NfcResult()
    /** Reader timed out before a tag was tapped. */
    data object Timeout : NfcResult()
    /** Error: hardware missing, tag read-only, malformed records, etc. */
    data class Error(val code: String) : NfcResult()
}

/**
 * Process-scoped buffer bridging the `nfc_read_tag` / `nfc_write_tag` tools to the NFC
 * reader-mode session in [ToolHostActivity]. Mirrors [BiometricResultBuffer].
 */
class NfcResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<NfcResult>>()

    fun register(requestId: String): CompletableDeferred<NfcResult> {
        val deferred = CompletableDeferred<NfcResult>()
        pending[requestId] = deferred
        return deferred
    }

    fun complete(requestId: String, result: NfcResult) {
        pending.remove(requestId)?.complete(result)
    }
}
