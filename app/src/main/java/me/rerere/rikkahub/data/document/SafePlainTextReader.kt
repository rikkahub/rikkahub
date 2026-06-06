package me.rerere.rikkahub.data.document

import me.rerere.rikkahub.data.rag.RagIngestLimits
import java.io.File

/**
 * Reads a file as plain text only when it is safe to do so. A thin delegator to the issue #84 guard
 * [RagIngestLimits.safeReadPlainText]: the binary-sniff and size-cap logic lives there and is NOT
 * reimplemented here, so the #84 invariants (a binary file is never decoded into garbage "content",
 * an oversized file is never fully read) hold byte-for-byte for both chat and RAG.
 *
 * Returns null when the file sniffs as binary or exceeds the input-size cap — i.e. "not safe to read
 * as text". The extractor turns that into an [DocumentExtractionResult.UnsupportedType].
 */
interface SafePlainTextReader {
    fun read(file: File): String?

    object Default : SafePlainTextReader {
        override fun read(file: File): String? = RagIngestLimits.safeReadPlainText(file)
    }
}
