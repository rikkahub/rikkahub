package me.rerere.rikkahub.data.document

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

    fun render(fileName: String, result: DocumentExtractionResult): String = when (result) {
        is DocumentExtractionResult.Success -> contentPrompt(fileName, result.text)
        DocumentExtractionResult.UnsupportedType -> note(fileName, "unsupported file type")
        DocumentExtractionResult.Empty -> note(fileName, "no extractable text")
        is DocumentExtractionResult.ParseFailed -> note(fileName, "could not be read")
        is DocumentExtractionResult.Rejected -> note(fileName, "too large to include")
    }

    private fun contentPrompt(fileName: String, text: String): String =
        """
        ## user sent a file: $fileName
        <content>
        ```
        $text
        ```
        </content>
        """.trimIndent()

    private fun note(fileName: String, reason: String): String =
        "## user attached a file: $fileName ($reason)"
}
