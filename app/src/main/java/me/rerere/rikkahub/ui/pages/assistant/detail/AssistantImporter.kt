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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant, List<Lorebook>) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant, List<Lorebook>) -> Unit
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
    fun parse(context: Context, json: JsonObject, background: String?): Assistant
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): Assistant {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull

        val prompt = buildString {
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

        return Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background
        )
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): Assistant {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull

        val prompt = buildString {
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

        return Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background
        )
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
): Assistant {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

internal fun parseLorebooksFromTavernCard(
    json: JsonObject,
    assistantName: String,
): List<Lorebook> {
    return runCatching {
        val data = json["data"]?.jsonObject ?: return emptyList()
        val candidates = buildLorebookCandidates(data)
        val firstValidLorebook = candidates.firstNotNullOfOrNull { candidate ->
            parseLorebookFromBookElement(candidate, assistantName)
        }
        firstValidLorebook?.let { listOf(it) } ?: emptyList()
    }.getOrDefault(emptyList())
}

private fun buildLorebookCandidates(data: JsonObject): List<kotlinx.serialization.json.JsonElement> {
    val keys = listOf("character_book", "characterBook", "worldbook", "lorebook")
    val candidates = buildList {
        keys.forEach { key ->
            data[key]?.let { add(it) }
        }

        val extensions = data["extensions"]?.jsonObject
        if (extensions != null) {
            keys.forEach { key ->
                extensions.values.forEach { ext ->
                    val obj = ext as? JsonObject ?: return@forEach
                    obj[key]?.let { add(it) }
                }
            }
        }
    }
    return candidates
}

private fun parseLorebookFromBookElement(
    bookElement: kotlinx.serialization.json.JsonElement,
    assistantName: String,
): Lorebook? {
    val bookObj = bookElement as? JsonObject ?: return null
    val entriesElement = bookObj["entries"] ?: return null
    val entries = parseLorebookEntries(entriesElement)
    if (entries.isEmpty()) return null

    val name = bookObj["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: "$assistantName Lorebook"

    return Lorebook(
        id = Uuid.random(),
        name = name,
        description = "",
        enabled = true,
        entries = entries,
    )
}

private fun parseLorebookEntries(entriesElement: kotlinx.serialization.json.JsonElement): List<PromptInjection.RegexInjection> {
    val entryObjects = when (entriesElement) {
        is JsonObject -> entriesElement.values.mapNotNull { it as? JsonObject }
        is JsonArray -> entriesElement.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    return entryObjects.mapNotNull { entry ->
        runCatching { parseLorebookEntry(entry) }.getOrNull()
    }
}

private fun parseLorebookEntry(entry: JsonObject): PromptInjection.RegexInjection {
    val keywords = parseLorebookEntryKeywords(entry)
    val extensions = entry["extensions"] as? JsonObject

    val comment = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val name = comment.ifEmpty { keywords.firstOrNull().orEmpty() }

    val disabledByDisable = entry["disable"]?.jsonPrimitiveOrNull?.booleanOrNull == true
    val enabledField = entry["enabled"]?.jsonPrimitiveOrNull?.booleanOrNull
    val enabled = when {
        disabledByDisable -> false
        enabledField == false -> false
        else -> true
    }

    val priority = entry["order"]?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["priority"]?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["insertion_order"]?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["insertion_order"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull()
        ?: 100

    val positionInt = entry["position"]?.jsonPrimitiveOrNull?.intOrNull
        ?: extensions?.get("position")?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["position"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull()
    val positionStr = entry["position"]?.jsonPrimitiveOrNull?.contentOrNull
    val position = when {
        positionInt != null -> mapSillyTavernPosition(positionInt)
        !positionStr.isNullOrBlank() -> mapSillyTavernPositionString(positionStr) ?: InjectionPosition.AFTER_SYSTEM_PROMPT
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }

    val injectDepth = entry["depth"]?.jsonPrimitiveOrNull?.intOrNull
        ?: extensions?.get("depth")?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["depth"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull()
        ?: 4

    val scanDepth = entry["scanDepth"]?.jsonPrimitiveOrNull?.intOrNull
        ?: entry["scan_depth"]?.jsonPrimitiveOrNull?.intOrNull
        ?: extensions?.get("scan_depth")?.jsonPrimitiveOrNull?.intOrNull
        ?: 4

    val caseSensitive = entry["caseSensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
        ?: entry["case_sensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
        ?: extensions?.get("case_sensitive")?.jsonPrimitiveOrNull?.booleanOrNull
        ?: false

    val constantActive = entry["constant"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false

    val content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()

    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        injectDepth = injectDepth,
        content = content,
        keywords = keywords,
        useRegex = false,
        caseSensitive = caseSensitive,
        scanDepth = scanDepth,
        constantActive = constantActive,
    )
}

private fun parseLorebookEntryKeywords(entry: JsonObject): List<String> {
    val element = entry["key"] ?: entry["keys"] ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }
}

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

private fun mapSillyTavernPositionString(position: String): InjectionPosition? {
    return when (position.trim().lowercase()) {
        "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
        "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
        "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
        "bottom_of_chat" -> InjectionPosition.BOTTOM_OF_CHAT
        "at_depth" -> InjectionPosition.AT_DEPTH
        else -> null
    }
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant, List<Lorebook>) -> Unit,
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
        val assistant = parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        val importedLorebooks = parseLorebooksFromTavernCard(json = json, assistantName = assistant.name)
        val updatedAssistant = if (importedLorebooks.isNotEmpty()) {
            assistant.copy(lorebookIds = assistant.lorebookIds + importedLorebooks.map { it.id })
        } else {
            assistant
        }
        onImport(updatedAssistant, importedLorebooks)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
