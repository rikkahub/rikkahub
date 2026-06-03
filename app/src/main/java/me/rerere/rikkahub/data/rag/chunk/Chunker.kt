package me.rerere.rikkahub.data.rag.chunk

/**
 * Pure, Android-free text chunker. Splits normalized text into overlapping windows so that
 * retrieval can match against bounded fragments while preserving cross-boundary context.
 *
 * Sizes are expressed in characters (a ~4 char/token approximation of the spec's ~512 token /
 * ~64 overlap default). Kept deterministic and side-effect free so the boundary/overlap behaviour
 * is unit-testable headless.
 */
object Chunker {
    const val DEFAULT_CHUNK_SIZE = 512
    const val DEFAULT_OVERLAP = 64

    /**
     * @param text source text (un-normalized is fine; CRLF/CR are normalized to LF and trailing
     *   whitespace runs collapsed before windowing).
     * @param chunkSize maximum characters per chunk; must be > 0.
     * @param overlap characters each chunk shares with its predecessor; must be in [0, chunkSize).
     * @return non-empty chunks in source order. Whitespace-only fragments are dropped. Empty input
     *   (after normalization) yields an empty list.
     */
    fun chunk(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        overlap: Int = DEFAULT_OVERLAP,
    ): List<String> {
        require(chunkSize > 0) { "chunkSize must be > 0, was $chunkSize" }
        require(overlap in 0 until chunkSize) { "overlap must be in [0, chunkSize), was $overlap" }

        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= chunkSize) return listOf(normalized)

        // The window advances by (chunkSize - overlap) each step; a positive stride is guaranteed
        // because overlap < chunkSize.
        val stride = chunkSize - overlap
        val chunks = ArrayList<String>()
        var start = 0
        while (start < normalized.length) {
            val end = minOf(start + chunkSize, normalized.length)
            val piece = normalized.substring(start, end)
            if (piece.isNotBlank()) chunks.add(piece)
            if (end == normalized.length) break
            start += stride
        }
        return chunks
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n").trim()
}
