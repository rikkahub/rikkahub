package me.rerere.rikkahub.data.document

import java.io.File

/**
 * Input to [DocumentTextExtractor.extract]: the on-disk [file] plus the provider-supplied [fileName]
 * and [mime] used for type resolution. [mime] is nullable because some callers (RAG) may only have a
 * file name to fall back on.
 */
data class DocumentSource(
    val file: File,
    val fileName: String,
    val mime: String?,
)
