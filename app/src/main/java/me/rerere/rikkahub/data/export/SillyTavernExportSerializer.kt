package me.rerere.rikkahub.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexSourceKind
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.exportFindRegex
import java.io.ByteArrayOutputStream

internal val RESERVED_LOREBOOK_EXTENSION_KEYS = setOf(
    "uid",
    "position",
    "depth",
    "selectiveLogic",
    "scan_depth",
    "match_whole_words",
    "case_sensitive",
    "role",
    "match_persona_description",
    "match_character_description",
    "match_character_personality",
    "match_character_depth_prompt",
    "match_scenario",
    "match_creator_notes",
    "probability",
    "useProbability",
    "display_index",
    "displayIndex",
    "entry_index",
)

internal fun buildRegexScript(regex: AssistantRegex): JsonObject {
    return buildJsonObject {
        put("scriptName", regex.name)
        put("findRegex", regex.exportFindRegex())
        put("replaceString", regex.replaceString)
        put("placement", buildJsonArray {
            regex.exportPlacements().forEach { add(JsonPrimitive(it)) }
        })
        put("disabled", !regex.enabled)
        put("markdownOnly", regex.visualOnly)
        put("promptOnly", regex.promptOnly)
        regex.minDepth?.let { put("minDepth", it) }
        regex.maxDepth?.let { put("maxDepth", it) }
        put("trimStrings", buildJsonArray {
            regex.trimStrings.forEach { add(JsonPrimitive(it)) }
        })
        put("runOnEdit", regex.runOnEdit)
        put("substituteRegex", regex.substituteRegex)
    }
}

internal fun InjectionPosition.toStCharacterBookPosition(): Int {
    return when (this) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
        InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
        InjectionPosition.AUTHOR_NOTE_TOP -> 2
        InjectionPosition.AUTHOR_NOTE_BOTTOM -> 3
        InjectionPosition.AT_DEPTH -> 4
        InjectionPosition.EXAMPLE_MESSAGES_TOP -> 5
        InjectionPosition.EXAMPLE_MESSAGES_BOTTOM -> 6
        InjectionPosition.OUTLET -> 7
        InjectionPosition.TOP_OF_CHAT,
        InjectionPosition.BOTTOM_OF_CHAT,
        -> 1
    }
}

internal fun MessageRole.toStPromptRole(): Int {
    return when (this) {
        MessageRole.USER -> 1
        MessageRole.ASSISTANT -> 2
        else -> 0
    }
}

internal fun sanitizeExportName(value: String, fallback: String): String {
    return value
        .trim()
        .ifBlank { fallback }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
}

internal fun rawJsonValue(rawValue: String): JsonElement {
    val trimmed = rawValue.trim()
    if (trimmed.isEmpty()) return JsonPrimitive(rawValue)
    trimmed.toBooleanStrictOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toIntOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    return runCatching { Json.parseToJsonElement(trimmed) }
        .getOrElse { JsonPrimitive(rawValue) }
}

internal fun PromptInjection.RegexInjection.exportProbabilityValue(): Int? {
    return probability ?: stMetadata["probability"]?.trim()?.toIntOrNull()
}

internal fun AssistantRegex.exportPlacements(): List<Int> {
    if (stPlacements.isNotEmpty()) return stPlacements.sorted()
    return buildList {
        if (AssistantAffectScope.USER in affectingScope) add(1)
        if (AssistantAffectScope.ASSISTANT in affectingScope) add(2)
    }.ifEmpty { listOf(2) }
}

internal fun AssistantRegex.shouldExportAsInlinePrompt(): Boolean {
    return sourceKind == AssistantRegexSourceKind.ST_INLINE_PROMPT &&
        sourceRef.isNotBlank() &&
        promptOnly &&
        stPlacements.isEmpty()
}

internal fun AssistantRegex.exportInlinePromptBlock(): String {
    val payload = buildJsonObject {
        put(findRegex, JsonPrimitive(replaceString))
    }
    val body = Json.encodeToString(JsonObject.serializer(), payload)
        .removePrefix("{")
        .removeSuffix("}")
    return "<regex>$body</regex>"
}

internal fun appendInlinePromptRegexes(
    content: String,
    regexes: List<AssistantRegex>,
): String {
    if (regexes.isEmpty()) return content
    val inlineBlocks = regexes.map(AssistantRegex::exportInlinePromptBlock)
    val base = content.trimEnd()
    return buildString {
        append(base)
        if (base.isNotEmpty()) {
            append('\n')
        }
        inlineBlocks.forEachIndexed { index, block ->
            if (index > 0) append('\n')
            append(block)
        }
    }
}

internal fun Assistant.firstAssistantPresetMessage(): String {
    return presetMessages
        .firstOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("") { it.text }
        .orEmpty()
}

internal fun loadBaseCardPngBytes(context: Context, assistant: Assistant): ByteArray {
    val avatarUri = (assistant.avatar as? Avatar.Image)?.url?.takeIf { it.isNotBlank() }?.toUri()
    val bitmap = avatarUri?.let { uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    } ?: createFallbackCardBitmap(assistant)

    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to encode PNG" }
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun createFallbackCardBitmap(assistant: Assistant): Bitmap {
    val width = 512
    val height = 768
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.parseColor("#1B2838"),
            Color.parseColor("#314E68"),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9A441")
        alpha = 110
    }
    canvas.drawCircle(width * 0.78f, height * 0.22f, width * 0.22f, accentPaint)

    val label = when (val avatar = assistant.avatar) {
        is Avatar.Emoji -> avatar.content.takeIf { it.isNotBlank() }
        else -> null
    } ?: assistant.stCharacterData?.name
        ?.takeIf { it.isNotBlank() }
        ?: assistant.name
            .takeIf { it.isNotBlank() }
        ?: "ST"

    val text = label.trim().ifEmpty { "ST" }.take(20)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = if (text.length <= 2) 180f else 72f
        isFakeBoldText = true
    }
    val textBounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, textBounds)
    val baseline = height / 2f - textBounds.exactCenterY()
    canvas.drawText(text, width / 2f, baseline, textPaint)

    return bitmap
}
