package me.rerere.rikkahub.data.rag.store

import ai.koog.rag.base.TextDocument

/**
 * A persisted, retrievable text fragment. Concrete [TextDocument] used as the `Document` type
 * parameter of [RoomVectorStore].
 *
 * @param chunkId stable unique id (also the Room primary key).
 * @param docId id of the source document this fragment came from.
 * @param sourceRef human-readable origin (e.g. the file name) for citation/debugging.
 * @param chunkIndex position of this fragment within its source document.
 * @param content the fragment text.
 */
data class Chunk(
    val chunkId: String,
    val docId: String,
    val sourceRef: String,
    val chunkIndex: Int,
    override val content: String,
) : TextDocument {
    override val id: String get() = chunkId

    override val metadata: Map<String, Any> = mapOf(
        "docId" to docId,
        "chunkIndex" to chunkIndex,
        "sourceRef" to sourceRef,
    )
}
