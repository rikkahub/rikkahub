package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection

import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.modifier.onClick
import java.io.ByteArrayOutputStream

/**
 * SillyTavern 角色卡导出器
 */
@Composable
fun AssistantExporter(
    assistant: Assistant,
    lorebooks: List<Lorebook>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val saveJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    exportToJson(context, assistant, lorebooks, it)
                    toaster.show("角色卡已导出", type = ToastType.Success)
                } catch (e: Exception) {
                    toaster.show("导出失败: ${e.message}", type = ToastType.Error)
                }
            }
        }
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    exportToPng(context, assistant, lorebooks, it)
                    toaster.show("角色卡 PNG 已导出", type = ToastType.Success)
                } catch (e: Exception) {
                    toaster.show("导出失败: ${e.message}", type = ToastType.Error)
                }
            }
        }
    }

    ListItem(
        headlineContent = { Text("导出为 JSON") },
        leadingContent = {
            Icon(
                imageVector = HugeIcons.File02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.onClick {
            saveJsonLauncher.launch("${assistant.name.ifBlank { "character" }}.json")
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    ListItem(
        headlineContent = { Text("导出为 PNG（含角色卡数据）") },
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Image02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.onClick {
            savePngLauncher.launch("${assistant.name.ifBlank { "character" }}.png")
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

// ===== 序列化逻辑 =====

fun buildTavernCardV3(
    assistant: Assistant,
    lorebooks: List<Lorebook>,
): JsonObject {
    return buildJsonObject {
        put("spec", "chara_card_v3")
        put("spec_version", "3.0")
        putJsonObject("data") {
            put("name", assistant.name)
            put("description", extractField(assistant.systemPrompt, "Description"))
            put("personality", extractField(assistant.systemPrompt, "Personality"))
            put("scenario", extractField(assistant.systemPrompt, "Scenario"))
            put("first_mes", assistant.presetMessages.firstOrNull()?.toText() ?: "")
            put("system_prompt", extractSystemPromptRaw(assistant.systemPrompt))
            put("mes_example", assistant.mesExample)
            put("creator_notes", assistant.creatorNotes)
            put("creator", assistant.creator)
            put("character_version", assistant.characterVersion)
            put("post_history_instructions", assistant.postHistoryInstructions)

            // alternate_greetings 包含 first_mes
            putJsonArray("alternate_greetings") {
                if (assistant.alternateGreetings.isNotEmpty()) {
                    assistant.alternateGreetings.forEach { msg ->
                        add(JsonPrimitive(msg.toText()))
                    }
                }
            }

            // tags - 从 Assistant 的 tag 名称获取
            putJsonArray("tags") {
                // 使用 internalTags 存储的名称
            }

            putJsonObject("extensions") {
                // 将来可扩展
            }

            // 知识库
            if (lorebooks.isNotEmpty()) {
                putJsonObject("character_book") {
                    putJsonObject("extensions") {}
                    putJsonArray("entries") {
                        lorebooks.forEach { lorebook ->
                            lorebook.entries.forEach { entry ->
                                add(buildWorldBookEntry(entry))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildWorldBookEntry(entry: PromptInjection.RegexInjection): JsonObject {
    return buildJsonObject {
        put("name", entry.name)
        putJsonArray("keys") {
            entry.keywords.forEach { add(JsonPrimitive(it)) }
        }
        put("content", entry.content)
        put("priority", entry.priority)
        put("constant", entry.constantActive)
        put("case_sensitive", entry.caseSensitive)
        put("position", when (entry.position) {
            InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
            InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
            InjectionPosition.TOP_OF_CHAT -> 2
            InjectionPosition.BOTTOM_OF_CHAT -> 2
            InjectionPosition.AT_DEPTH -> 4
        })
        put("selective", false)
        put("insertion_order", 0)
        put("enabled", entry.enabled)
        putJsonObject("extensions") {}
    }
}

/** 从拼接的 systemPrompt 中提取指定章节内容 */
private fun extractField(prompt: String, section: String): String {
    val regex = Regex("""## $section of the character\s*\n(.+?)(?:\n##|\Z)""", RegexOption.DOT_MATCHES_ALL)
    return regex.find(prompt)?.groupValues?.get(1)?.trim() ?: ""
}

/** 提取原始的 system_prompt（去除 RikkaHub 拼接的部分） */
private fun extractSystemPromptRaw(prompt: String): String {
    val lines = prompt.lines()
    val sb = StringBuilder()
    for (line in lines) {
        if (line.startsWith("## ")) break
        if (!line.startsWith("You are roleplaying as")) {
            sb.appendLine(line)
        }
    }
    return sb.toString().trim()
}

private fun UIMessage.toText(): String {
    return this.parts.joinToString("") { part ->
        when (part) {
            is me.rerere.ai.ui.UIMessagePart.Text -> part.text
            else -> ""
        }
    }
}

// ===== 文件导出 =====

private suspend fun exportToJson(
    context: Context,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    uri: Uri,
) = withContext(Dispatchers.IO) {
    val json = buildTavernCardV3(assistant, lorebooks)
    val jsonString = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)
    context.contentResolver.openOutputStream(uri)?.use { output ->
        output.write(jsonString.toByteArray(Charsets.UTF_8))
    } ?: throw Exception("无法打开文件")
}

private suspend fun exportToPng(
    context: Context,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    uri: Uri,
) = withContext(Dispatchers.IO) {
    val json = buildTavernCardV3(assistant, lorebooks)
    val jsonString = Json.encodeToString(JsonElement.serializer(), json)

    // 获取头像 bitmap
    val avatarBitmap = getAvatarBitmap(context, assistant)

    // 压缩为 PNG
    val pngBytes = ByteArrayOutputStream().apply {
        avatarBitmap.compress(CompressFormat.PNG, 100, this)
    }.toByteArray()

    // 注入 tEXt chunk (ccv3)
    val resultBytes = injectTextChunk(pngBytes, "ccv3", jsonString)

    context.contentResolver.openOutputStream(uri)?.use { output ->
        output.write(resultBytes)
    } ?: throw Exception("无法打开文件")

    // 回收 bitmap（占位图也是新创建的，可以回收）
    avatarBitmap.recycle()
}

private fun getAvatarBitmap(context: Context, assistant: Assistant): Bitmap {
    // 从 avatar 加载真实头像
    val avatar = assistant.avatar
    if (avatar is me.rerere.rikkahub.data.model.Avatar.Image) {
        try {
            val uri = Uri.parse(avatar.url)
            // 处理 file:// 和 content:// 两种 URI
            val bitmap = if (uri.scheme == "file") {
                BitmapFactory.decodeFile(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
            if (bitmap != null) return bitmap
        } catch (_: Exception) { }
    }
    // 兜底：返回 RikkaHub 占位图
    return getFallbackBitmap()
}

private fun getFallbackBitmap(): Bitmap {
    // 创建一个 512x512 的占位图
    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.parseColor("#2C2C2E"))
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    canvas.drawText("RikkaHub", 256f, 256f, paint)
    return bitmap
}

/**
 * 在 PNG 数据中注入 tEXt 块（在 IEND 之前）
 */
private fun injectTextChunk(pngData: ByteArray, keyword: String, text: String): ByteArray {
    val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
    val textBytes = text.toByteArray(Charsets.ISO_8859_1)

    // tEXt chunk: length(4) + type(4) + keyword + 0 + text + crc(4)
    val chunkData = keywordBytes + byteArrayOf(0) + textBytes
    val chunkLength = chunkData.size
    val chunkType = "tEXt".toByteArray(Charsets.ISO_8859_1)

    val crcInput = chunkType + chunkData
    val crc = calculateCRC(crcInput)

    val chunk = ByteArrayOutputStream().apply {
        // length (big-endian)
        write(byteArrayOf(
            (chunkLength shr 24).toByte(),
            (chunkLength shr 16).toByte(),
            (chunkLength shr 8).toByte(),
            chunkLength.toByte()
        ))
        write(chunkType)
        write(chunkData)
        write(byteArrayOf(
            (crc shr 24).toByte(),
            (crc shr 16).toByte(),
            (crc shr 8).toByte(),
            crc.toByte()
        ))
    }.toByteArray()

    // 在 IEND 之前插入
    val iendIndex = pngData.size - 12  // IEND chunk is 12 bytes
    return ByteArrayOutputStream().apply {
        write(pngData, 0, iendIndex)
        write(chunk)
        write(pngData, iendIndex, 12)  // original IEND
    }.toByteArray()
}

/**
 * CRC-32 计算（PNG 规范：使用 ISO 8859-1 编码）
 */
private val crcTable by lazy {
    IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if ((c and 1) == 1) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
        }
        c
    }
}

private fun calculateCRC(data: ByteArray): Int {
    var c = 0xFFFFFFFF.toInt()
    for (b in data) {
        c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
    }
    return c xor 0xFFFFFFFF.toInt()
}
