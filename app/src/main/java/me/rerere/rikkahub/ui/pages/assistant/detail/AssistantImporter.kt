package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
    onLorebooks: ((List<Lorebook>) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(
            onImport = { result ->
                onUpdate(result.assistant)
                result.lorebooks.takeIf { it.isNotEmpty() }?.let { books ->
                    onLorebooks?.invoke(books)
                }
            }
        )
    }
}

/**
 * 导入结果，包含 Assistant 和关联的 Lorebook
 */
data class TavernImportResult(
    val assistant: Assistant,
    val lorebooks: List<Lorebook> = emptyList(),
)

@Composable
private fun SillyTavernImporter(
    onImport: (TavernImportResult) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult {
        val data = json["data"]?.jsonObject
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
        val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val creator = data["creator"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val characterVersion = data["character_version"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""

        // 备用开场白
        val alternateGreetings = mutableListOf<UIMessage>()
        data["alternate_greetings"]?.jsonArray?.forEach { elem ->
            elem.jsonPrimitiveOrNull?.contentOrNull?.let { text ->
                if (text.isNotBlank()) {
                    alternateGreetings.add(UIMessage.assistant(text))
                }
            }
        }

        // 知识库
        val lorebooks = parseCharacterBook(data)

        val prompt = buildPrompt(name, system, description, personality, scenario)

        val assistant = Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            alternateGreetings = alternateGreetings,
            systemPrompt = prompt,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            creator = creator,
            characterVersion = characterVersion,
            postHistoryInstructions = postHistoryInstructions,
            background = background
        )
        return TavernImportResult(
            assistant = assistant.copy(
                lorebookIds = lorebooks.map { it.id }.toSet()
            ),
            lorebooks = lorebooks
        )
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult {
        val data = json["data"]?.jsonObject
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
        val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val creator = data["creator"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val characterVersion = data["character_version"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""

        // 备用开场白
        val alternateGreetings = mutableListOf<UIMessage>()
        data["alternate_greetings"]?.jsonArray?.forEach { elem ->
            elem.jsonPrimitiveOrNull?.contentOrNull?.let { text ->
                if (text.isNotBlank()) {
                    alternateGreetings.add(UIMessage.assistant(text))
                }
            }
        }

        // 知识库
        val lorebooks = parseCharacterBook(data)

        val prompt = buildPrompt(name, system, description, personality, scenario)

        val assistant = Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            alternateGreetings = alternateGreetings,
            systemPrompt = prompt,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            creator = creator,
            characterVersion = characterVersion,
            postHistoryInstructions = postHistoryInstructions,
            background = background
        )
        return TavernImportResult(
            assistant = assistant.copy(
                lorebookIds = lorebooks.map { it.id }.toSet()
            ),
            lorebooks = lorebooks
        )
    }
}

// ===== 知识库解析 =====

private fun parseCharacterBook(data: JsonObject): List<Lorebook> {
    val lorebooks = mutableListOf<Lorebook>()

    // 从 character_book 解析
    data["character_book"]?.jsonObject?.let { book ->
        val entries = mutableListOf<PromptInjection.RegexInjection>()
        book["entries"]?.jsonArray?.forEach { entryObj ->
            entryObj.jsonObject?.let { entry ->
                entries.add(parseWorldBookEntry(entry))
            }
        }
        if (entries.isNotEmpty()) {
            val charName = data["name"]?.jsonPrimitive?.contentOrNull ?: "角色"
            lorebooks.add(
                Lorebook(
                    name = "${charName}的知识库",
                    description = data["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    entries = entries
                )
            )
        }
    }

    // 从 extensions.world_book 解析
    data["extensions"]?.jsonObject?.let { exts ->
        exts["world_book"]?.jsonObject?.let { wb ->
            val entries = mutableListOf<PromptInjection.RegexInjection>()
            wb["entries"]?.jsonArray?.forEach { entryObj ->
                entryObj.jsonObject?.let { entry ->
                    entries.add(parseWorldBookEntry(entry))
                }
            }
            if (entries.isNotEmpty()) {
                lorebooks.add(
                    Lorebook(
                        name = "嵌入世界书",
                        entries = entries
                    )
                )
            }
        }
    }

    return lorebooks
}

/**
 * 将 SillyTavern position 整数映射为 RikkaHub InjectionPosition
 */
private fun mapSillyTavernPosition(position: Int): InjectionPosition {
    return when (position) {
        0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
        2 -> InjectionPosition.TOP_OF_CHAT
        3 -> InjectionPosition.TOP_OF_CHAT // After Examples -> 聊天历史开头
        4 -> InjectionPosition.AT_DEPTH    // @Depth 模式
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}

private fun parseWorldBookEntry(entry: JsonObject): PromptInjection.RegexInjection {
    val keys = mutableListOf<String>()
    entry["keys"]?.jsonArray?.forEach { key ->
        key.jsonPrimitiveOrNull?.contentOrNull?.let { keys.add(it) }
    }
    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = entry["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        keywords = keys,
        content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
        priority = entry["priority"]?.jsonPrimitiveOrNull?.let { it.content.toIntOrNull() } ?: 0,
        position = mapSillyTavernPosition(
            entry["position"]?.jsonPrimitiveOrNull?.let { it.content.toIntOrNull() } ?: 1
        ),
        constantActive = entry["constant"]?.jsonPrimitiveOrNull?.let {
            it.content.toBoolean()
        } ?: false,
        caseSensitive = entry["case_sensitive"]?.jsonPrimitiveOrNull?.let {
            it.content.toBoolean()
        } ?: false,
        enabled = entry["enabled"]?.jsonPrimitiveOrNull?.let {
            it.content.toBoolean()
        } ?: true,
    )
}

// ===== Prompt 构建 =====

private fun buildPrompt(
    name: String,
    system: String?,
    description: String?,
    personality: String?,
    scenario: String?
): String {
    return buildString {
        appendLine("You are roleplaying as $name.")
        appendLine()
        if (!system.isNullOrBlank()) {
            appendLine(system)
            appendLine()
        }
        appendLine("## Description of the character")
        appendLine(description ?: "Empty")
        appendLine()
        appendLine("## Personality of the character")
        appendLine(personality ?: "Empty")
        appendLine()
        appendLine("## Scenario")
        append(scenario ?: "Empty")
    }
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): TavernImportResult {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (TavernImportResult) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val result = parseAssistantFromJson(context, json, backgroundStr)
        onImport(result)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
