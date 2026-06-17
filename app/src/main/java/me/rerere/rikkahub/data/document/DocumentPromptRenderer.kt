package me.rerere.rikkahub.data.document

import me.rerere.common.text.UntrustedContentFraming

/**
 * Renders the prompt text for one chat attachment from its typed [DocumentExtractionResult]
 * (issue #102). The bug this replaces: the old chat path wrapped whatever string the reader returned
 * inside `<content>...</content>`, so a parser error like `[ERROR, failed to read file: x]` was fed
 * to the model AS IF it were the document's text, poisoning context.
 *
 * Policy:
 *  - [DocumentExtractionResult.Success]  -> the real text inside a `<content>` block.
 *  - every non-success outcome           -> a short, fixed metadata-only note OUTSIDE any `<content>`
 *                                           block, with NO parser/reason string. This keeps the model
 *                                           aware an attachment existed without treating an error as
 *                                           content. `ParseFailed.reason` / `Rejected.reason` are
 *                                           diagnostics only and are intentionally not emitted here.
 *
 * Pure function over the result so it is JVM unit-testable without Android URI / disk IO.
 */
object DocumentPromptRenderer {
    private val controlCharacters = Regex("[\\p{Cc}\\p{Cf}]+")
    private val repeatedSpaces = Regex(" +")

    fun render(fileName: String, result: DocumentExtractionResult): String {
        val safeFileName = sanitizePromptSafeFileName(fileName)

        return when (result) {
            is DocumentExtractionResult.Success -> contentPrompt(safeFileName, result.text)
            DocumentExtractionResult.UnsupportedType -> note(safeFileName, "unsupported file type")
            DocumentExtractionResult.Empty -> note(safeFileName, "no extractable text")
            is DocumentExtractionResult.ParseFailed -> note(safeFileName, "could not be read")
            is DocumentExtractionResult.Rejected -> note(safeFileName, "too large to include")
        }
    }

    private fun contentPrompt(fileName: String, text: String): String =
        if (text.isBlank()) {
            note(fileName, "no extractable text")
        } else {
            """
            ## user sent a file: $fileName
            <content>
            ```
            ${UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE}
            ${UntrustedContentFraming.escape(text)}
            ```
            </content>
            """.trimIndent()
        }

    private fun note(fileName: String, reason: String): String =
        "## user attached a file: $fileName ($reason)"

    private fun sanitizePromptSafeFileName(fileName: String): String =
        UntrustedContentFraming.escape(fileName
            .replace(controlCharacters, " ")
            .trim()
            .replace(repeatedSpaces, " "),
        )
}
