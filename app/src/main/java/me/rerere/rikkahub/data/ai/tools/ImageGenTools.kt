package me.rerere.rikkahub.data.ai.tools

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.antigravityProvider
import me.rerere.rikkahub.data.datastore.chatGptProvider
import me.rerere.rikkahub.data.datastore.withManagedImageModels
import me.rerere.rikkahub.data.files.FilesManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The `generate_image` agent tool: render an image from a text prompt — or EDIT/compose existing
 * conversation images (image-to-image) when `images` is set — through whichever MANAGED image provider
 * is configured: Gagy (the managed Google provider — Gemini "nano-banana") or ChatGPT/Codex (gpt-image-2). Deferred —
 * only added when at least one managed image provider is set up (see ChatService baseTools), so a chat
 * with a model that has no native image generation (e.g. Anthropic) can still produce/edit images
 * in-conversation. Both managed providers run through the same Provider.generateImage/editImage wire as
 * the image-generation page, so this stays a thin adapter over that path.
 *
 * Image references: the agent can't pass binary, so it edits by url. [Tool.systemPrompt] (which sees
 * the conversation) lists the `file://` image urls already present; the model copies the chosen url(s)
 * into `images`, and [Tool.execute] (which does NOT see the conversation) resolves each back to a local
 * file via [FilesManager.resolveManagedFile] — a containment guard that rejects any path outside the
 * managed upload dir.
 *
 * The result is saved to local chat files and returned as [UIMessagePart.Image]s (so they render
 * inline, see GenerateImageToolUI) plus a short [UIMessagePart.Text] note. How much of this the next
 * turn feeds back to the model is provider-dependent: OpenAI/Gemini serialize only the text of a tool
 * result, while Claude also re-sends the image — acceptable, just not uniform across providers.
 */
@OptIn(ExperimentalEncodingApi::class)
fun createImageGenTools(
    settings: Settings,
    filesManager: FilesManager,
    providerManager: ProviderManager,
): List<Tool> {
    // Self-gate: resolve a managed IMAGE model (Gagy Gemini and/or Codex gpt-image-2) up front,
    // preferring the one the image-generation page has selected; absent → no tool (stays deferred).
    val managedProviderIds = setOfNotNull(
        settings.antigravityProvider()?.id,
        settings.chatGptProvider()?.id,
    )
    if (managedProviderIds.isEmpty()) return emptyList()
    val augmented = settings.copy(providers = settings.withManagedImageModels())
    val candidates: List<Pair<Model, ProviderSetting>> = augmented.providers
        .filter { it.id in managedProviderIds }
        .flatMap { ps -> ps.models.filter { it.type == ModelType.IMAGE }.map { it to ps } }
    if (candidates.isEmpty()) return emptyList()
    val (model, providerSetting) = candidates
        .firstOrNull { it.first.id == settings.imageGenerationModelId }
        ?: candidates.first()
    val provider = providerManager.getProviderByType(providerSetting)

    return listOf(
        Tool(
            name = "generate_image",
            description = """
                Generate an image from a text description, or edit/compose existing image(s) from this
                conversation when you set the `images` parameter.
                Use this when the user asks to create, draw, render, design, or EDIT an image,
                illustration, logo, icon, or artwork. Provide a single detailed prompt describing the
                desired image (or, when editing, the change to make). To edit/compose images already in
                this conversation instead of generating from scratch, set `images` to a JSON array of
                their exact url(s) — the system note lists which urls are available.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "A detailed description of the image to generate, or the edit " +
                                    "instruction to apply when `images` is set."
                            )
                        })
                        put("images", buildJsonObject {
                            put("type", "array")
                            put(
                                "description",
                                "Optional JSON ARRAY of image url strings to edit/compose " +
                                    "(image-to-image). MUST be an array even for a single image, e.g. " +
                                    "[\"file:///…\"] — do NOT pass a bare string. Use the exact url(s) " +
                                    "of image(s) already in this conversation (the system note lists " +
                                    "them). Omit entirely to generate from scratch."
                            )
                            putJsonObject("items") { put("type", "string") }
                        })
                    },
                    required = listOf("prompt"),
                )
            },
            systemPrompt = { _, messages ->
                val available = messages.conversationImageUrls()
                if (available.isEmpty()) {
                    ""
                } else {
                    buildString {
                        appendLine(
                            "Images available in this conversation for the generate_image tool's " +
                                "`images` parameter — put the exact url(s) into the `images` ARRAY " +
                                "(e.g. [\"<url>\"]) to edit/compose them:"
                        )
                        available.forEach { appendLine("- $it") }
                    }
                }
            },
            execute = { args ->
                val obj = args.jsonObject
                val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("prompt is required")
                // A present `images` array is STRICT input: every element must be a nonblank url that
                // resolves to a conversation image. Silently dropping a blank/unresolvable source would
                // compose the wrong result (or fall back to plain generation), so reject the call and
                // name the offending entries instead. Absent or empty array → text-to-image generation.
                // `images` SHOULD be a json array, but tolerate a model that passes a bare url string
                // (a common LLM mistake) — normalize both shapes to a list. null/absent/other → none.
                val requestedUrls: List<String> = when (val el = obj["images"]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> el.map { (it as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
                    is JsonPrimitive -> listOf(el.contentOrNull?.trim().orEmpty())
                    else -> emptyList()
                }
                val sourcePaths = if (requestedUrls.isEmpty()) {
                    emptyList()
                } else {
                    val blanks = requestedUrls.count { it.isEmpty() }
                    if (blanks > 0) {
                        error("The `images` array has $blanks blank entr${if (blanks == 1) "y" else "ies"}; every entry must be the url of an image in this conversation")
                    }
                    val resolved = withContext(Dispatchers.IO) {
                        requestedUrls.map { url ->
                            url to filesManager.resolveManagedFile(url.toUri())?.absolutePath
                        }
                    }
                    val unresolved = resolved.filter { it.second == null }.map { it.first }
                    if (unresolved.isNotEmpty()) {
                        error("These referenced images are not available in this conversation: ${unresolved.joinToString(", ")}")
                    }
                    resolved.mapNotNull { it.second }
                }

                val flow = if (sourcePaths.isNotEmpty()) {
                    provider.editImage(
                        providerSetting,
                        ImageEditParams(
                            model = model,
                            prompt = prompt,
                            images = sourcePaths,
                            customHeaders = model.customHeaders,
                            customBody = model.customBodies,
                        )
                    )
                } else {
                    provider.generateImage(
                        providerSetting,
                        ImageGenerationParams(
                            model = model,
                            prompt = prompt,
                            customHeaders = model.customHeaders,
                            customBody = model.customBodies,
                        )
                    )
                }
                // Drop streaming partials (Codex gpt-image streams refinement passes); keep finals only.
                val items = flow.filter { !it.partial }.toList()
                if (items.isEmpty()) {
                    error("Image ${if (sourcePaths.isNotEmpty()) "edit" else "generation"} produced no image — try again or rephrase the prompt")
                }
                val uris = withContext(Dispatchers.IO) {
                    filesManager.createChatFilesByByteArrays(items.map { Base64.decode(it.data) })
                }
                buildList {
                    uris.forEach { add(UIMessagePart.Image(url = it.toString())) }
                    val verb = if (sourcePaths.isNotEmpty()) "Edited" else "Generated"
                    add(UIMessagePart.Text("$verb ${uris.size} image(s) for prompt: \"$prompt\"."))
                }
            }
        )
    )
}

/**
 * The `file://` image urls present in the conversation — the editable references for generate_image.
 * Covers both directly-attached images (top-level [UIMessagePart.Image]) AND images produced by a
 * prior tool call (nested in [UIMessagePart.Tool.output]) so "edit the image you just generated" works.
 */
private fun List<UIMessage>.conversationImageUrls(): List<String> =
    asSequence()
        .flatMap { it.parts.asSequence() }
        .flatMap { part ->
            when (part) {
                is UIMessagePart.Image -> sequenceOf(part)
                is UIMessagePart.Tool -> part.output.asSequence().filterIsInstance<UIMessagePart.Image>()
                else -> emptySequence()
            }
        }
        .map { it.url }
        .filter { it.startsWith("file://") }
        .distinct()
        .toList()
