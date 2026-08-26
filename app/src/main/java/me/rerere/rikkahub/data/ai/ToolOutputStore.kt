package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File

private const val TOOL_OUTPUT_TAG = "ToolOutputStore"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

class ToolOutputStore(private val context: Context) {
    fun storeIfNeeded(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filterNot { it is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        val fullText = textParts.joinToString("\n", transform = UIMessagePart.Text::text)
        val safeId = toolCallId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "tool_output" }
        val fileName = "$safeId.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply(File::mkdirs)
        File(outputDir, fileName).writeText(fullText)
        Log.i(TOOL_OUTPUT_TAG, "Stored truncated tool output: $fileName ($totalChars chars)")

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + nonTextParts
    }
}
