package me.rerere.rikkahub.data.ai.tools.local

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.Charset
import java.util.Base64

/**
 * Phase 25 — NDEF <-> LLM-friendly record JSON codec. Shared by [ToolHostActivity]'s NFC
 * reader mode and `NfcToolsTest`.
 *
 * LLM-facing record shape:
 *   - `{ kind: "text", value: <decoded text> }`            (RTD_TEXT)
 *   - `{ kind: "uri",  value: <decoded URI> }`             (RTD_URI)
 *   - `{ kind: "raw",  value: <base64 payload>, tnf: Int, type_b64: <base64 type> }`
 */
object NfcNdefCodec {

    // RTD_URI prefix table — index 0 means "no prefix".
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://",
        "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    /** Decode an [NdefMessage] into the LLM-facing JSON array. */
    fun decode(message: NdefMessage): JsonArray = buildJsonArray {
        for (record in message.records) {
            addJsonObject {
                when {
                    record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                        put("kind", "text")
                        put("value", decodeTextPayload(record.payload))
                    }
                    record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        record.type.contentEquals(NdefRecord.RTD_URI) -> {
                        put("kind", "uri")
                        put("value", decodeUriPayload(record.payload))
                    }
                    else -> {
                        put("kind", "raw")
                        put("value", b64(record.payload))
                        put("tnf", record.tnf.toInt())
                        put("type_b64", b64(record.type))
                    }
                }
            }
        }
    }

    /** Build an [NdefMessage] from the LLM-supplied record array. Throws on bad input. */
    fun encode(records: JsonArray): NdefMessage {
        require(records.isNotEmpty()) { "records must not be empty" }
        val ndefRecords = records.map { element ->
            val obj = element.jsonObject
            when (val kind = obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val value = obj["value"]?.jsonPrimitive?.contentOrNull
                        ?: error("text record missing value")
                    NdefRecord.createTextRecord(null, value)
                }
                "uri" -> {
                    val value = obj["value"]?.jsonPrimitive?.contentOrNull
                        ?: error("uri record missing value")
                    NdefRecord.createUri(value)
                }
                "raw" -> {
                    val payload = obj["value"]?.jsonPrimitive?.contentOrNull
                        ?: error("raw record missing value")
                    val tnf = obj["tnf"]?.jsonPrimitive?.contentOrNull?.toShortOrNull()
                        ?: error("raw record missing tnf")
                    val typeB64 = obj["type_b64"]?.jsonPrimitive?.contentOrNull
                        ?: error("raw record missing type_b64")
                    NdefRecord(tnf, unb64(typeB64), ByteArray(0), unb64(payload))
                }
                else -> error("unknown record kind: $kind")
            }
        }
        return NdefMessage(ndefRecords.toTypedArray())
    }

    /** RTD_TEXT payload: status byte (encoding + lang length) + lang code + text. */
    internal fun decodeTextPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val langLength = status and 0x3F
        val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charset.forName("UTF-16")
        val textStart = 1 + langLength
        if (textStart > payload.size) return ""
        return String(payload, textStart, payload.size - textStart, charset)
    }

    /** RTD_URI payload: 1-byte prefix index + remaining URI bytes. */
    internal fun decodeUriPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefixIndex = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(prefixIndex) { "" }
        val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        return prefix + rest
    }
}
