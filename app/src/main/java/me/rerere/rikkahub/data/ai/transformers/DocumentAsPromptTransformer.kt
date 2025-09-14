package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val file = document.url.toUri().toFile()
                                val content = when (document.mime) {
                                    "application/pdf" -> parsePdfAsText(file)
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(
                                        file
                                    )

                                    else -> file.readText()
                                }
                                val prompt = """
                  ## user sent a file: ${document.fileName}
                  <content>
                  ```
                  $content
                  ```
                  </content>
                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        val module = Python.getInstance().getModule("pdf_util")
        val reader = module.callAttr("extract_text_from_pdf", file.absolutePath).toString()
        return reader
    }

    private fun parseDocxAsText(file: File): String {
        val module = Python.getInstance().getModule("docx_util")
        val reader = module.callAttr("extract_text_from_docx", file.absolutePath).toString()
        return reader
    }
}
