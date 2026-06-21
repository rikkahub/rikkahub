package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.codexGenerateImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.chatGptAccessToken
import me.rerere.rikkahub.data.files.FilesManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The `generate_image` agent tool: render an image from a text prompt via the ChatGPT (Codex)
 * backend's hosted image_generation tool (gpt-image-2). Deferred — only added when a ChatGPT
 * provider is configured (see ChatService baseTools), so a chat with a model that has no native
 * image generation (e.g. Anthropic) can still produce images in-conversation.
 *
 * The generated base64 is saved to a local chat file and returned as an [UIMessagePart.Image] (so it
 * renders inline, see GenerateImageToolUI) plus a short [UIMessagePart.Text] note. How much of this the
 * next turn feeds back to the model is provider-dependent: OpenAI/Gemini serialize only the text of a
 * tool result, while Claude also re-sends the image — acceptable (the model can then see the image it
 * produced), just not uniform across providers.
 */
@OptIn(ExperimentalEncodingApi::class)
fun createImageGenTools(settings: Settings, filesManager: FilesManager): List<Tool> {
    val accessToken = settings.chatGptAccessToken() ?: return emptyList()
    return listOf(
        Tool(
            name = "generate_image",
            description = """
                Generate an image from a text description.
                Use this when the user asks to create, draw, render, or design an image, illustration,
                logo, icon, or artwork. Provide a single detailed prompt describing the desired image.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "A detailed description of the image to generate.")
                        })
                    },
                    required = listOf("prompt"),
                )
            },
            execute = { args ->
                val prompt = args.jsonObject["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error("prompt is required")
                val base64Images = codexGenerateImage(accessToken = accessToken, prompt = prompt)
                val uris = withContext(Dispatchers.IO) {
                    filesManager.createChatFilesByByteArrays(base64Images.map { Base64.decode(it) })
                }
                buildList {
                    uris.forEach { add(UIMessagePart.Image(url = it.toString())) }
                    add(UIMessagePart.Text("Generated ${uris.size} image(s) for prompt: \"$prompt\"."))
                }
            }
        )
    )
}
