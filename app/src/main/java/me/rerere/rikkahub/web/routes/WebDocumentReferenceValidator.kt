package me.rerere.rikkahub.web.routes

import me.rerere.ai.ui.UIMessagePart

suspend fun validateWebDocumentReferences(
    parts: List<UIMessagePart>,
    isManagedUpload: suspend (String) -> Boolean,
): String? {
    return parts
        .filterIsInstance<UIMessagePart.Document>()
        .firstNotNullOfOrNull { document ->
            when {
                document.url.startsWith("content:", ignoreCase = true) ->
                    "Document '${document.fileName}' uses an unsupported content URI; upload the file through the Web API first"

                !document.url.startsWith("file:", ignoreCase = true) ->
                    "Document '${document.fileName}' must reference a managed uploaded file"

                !isManagedUpload(document.url) ->
                    "Document '${document.fileName}' must reference a managed uploaded file"

                else -> null
            }
        }
}
