package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenRouterRouting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.litert.LiteRtCatalog
import me.rerere.locallm.litert.LiteRtCatalogEntry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.locallm.SettingLocalLlmViewModel
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        if (!provider.builtIn) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = { Text(type.simpleName ?: "") },
                        selected = provider::class == type,
                        onClick = { onEdit(provider.convertTo(type)) }
                    )
                }
            }
        }

        when (provider) {
            is ProviderSetting.OpenAI -> {
                ProviderConfigureOpenAI(provider, onEdit)
            }

            is ProviderSetting.Google -> {
                ProviderConfigureGoogle(provider, onEdit)
            }

            is ProviderSetting.Claude -> {
                ProviderConfigureClaude(provider, onEdit)
            }

            is ProviderSetting.AICore -> {
                ProviderConfigureAICore(provider, onEdit)
            }

            is ProviderSetting.LiteRtLocal -> {
                ProviderConfigureLiteRT(provider, onEdit)
            }
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
        is ProviderSetting.AICore -> "" // on-device, no API key
        is ProviderSetting.LiteRtLocal -> "" // on-device, no API key
    }
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.AICore -> "" // on-device, no base URL
        is ProviderSetting.LiteRtLocal -> "" // on-device, no base URL
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        ProviderSetting.AICore::class -> ""
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )

        ProviderSetting.AICore::class -> ProviderSetting.AICore(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
        )

        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
            is ProviderSetting.AICore -> return "" // on-device, no base URL
            is ProviderSetting.LiteRtLocal -> return "" // on-device, no base URL
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
        is ProviderSetting.AICore -> ""
        is ProviderSetting.LiteRtLocal -> ""
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.AICore -> this // no base URL to reset
        is ProviderSetting.LiteRtLocal -> this // no base URL to reset
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.AICore -> return true // no base URL concept
        is ProviderSetting.LiteRtLocal -> return true // no base URL concept
    }
    return baseUrl == defaultBaseUrlForReset()
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    if (!provider.useResponseApi) {
        OutlinedTextField(
            value = provider.chatCompletionsPath,
            onValueChange = { onEdit(provider.copy(chatCompletionsPath = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_path)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !provider.builtIn,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_response_api))
        Switch(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            }
        )
    }

    // OpenRouter is the only OpenAI-compatible host where this does anything: it gates
    // the cache_control breakpoints required by Anthropic/Gemini/Qwen models routed
    // through it. Other models on OpenRouter cache automatically regardless.
    if (provider.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(id = R.string.setting_provider_page_claude_prompt_caching),
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = provider.promptCaching,
                onCheckedChange = {
                    onEdit(provider.copy(promptCaching = it))
                }
            )
        }
        OpenRouterRoutingSection(
            routing = provider.routing,
            onChange = { onEdit(provider.copy(routing = it)) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_include_history_reasoning))
        Switch(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) }
        )
    }
}

@Composable
private fun OpenRouterRoutingSection(
    routing: OpenRouterRouting,
    onChange: (OpenRouterRouting) -> Unit,
) {
    fun listToText(list: List<String>) = list.joinToString(", ")
    fun textToList(text: String) = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text("OpenRouter routing", style = MaterialTheme.typography.titleSmall)

    // Sort
    val sortOptions = listOf(null, "price", "throughput", "latency")
    val sortLabels = listOf("Auto", "Price", "Throughput", "Latency")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        sortOptions.forEachIndexed { index, option ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sortOptions.size),
                selected = routing.sort == option,
                onClick = { onChange(routing.copy(sort = option)) },
                label = { Text(sortLabels[index]) },
            )
        }
    }

    OutlinedTextField(
        value = listToText(routing.order),
        onValueChange = { onChange(routing.copy(order = textToList(it))) },
        label = { Text("Provider order (slugs, comma-separated)") },
        placeholder = { Text("anthropic, google-vertex") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = listToText(routing.only),
        onValueChange = { onChange(routing.copy(only = textToList(it))) },
        label = { Text("Only these providers") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = listToText(routing.ignore),
        onValueChange = { onChange(routing.copy(ignore = textToList(it))) },
        label = { Text("Ignore these providers") },
        modifier = Modifier.fillMaxWidth(),
    )

    RoutingToggle("Allow fallbacks beyond the list", routing.allowFallbacks) {
        onChange(routing.copy(allowFallbacks = it))
    }
    RoutingToggle("Require providers to support all parameters", routing.requireParameters) {
        onChange(routing.copy(requireParameters = it))
    }
    RoutingToggle("Block data-collecting providers", routing.dataCollection == "deny") {
        onChange(routing.copy(dataCollection = if (it) "deny" else null))
    }
    RoutingToggle("Zero Data Retention only", routing.zdr) {
        onChange(routing.copy(zdr = it))
    }

    Text(
        "Max price (USD per 1M tokens). Leave empty or tap the clear icon for no price limit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Local edit buffers so a partial decimal entry ("0.") isn't snapped away while typing:
    // the parsed Double drives routing, the raw text drives the field.
    var promptPriceText by remember { mutableStateOf(routing.maxPricePrompt?.toString() ?: "") }
    var completionPriceText by remember { mutableStateOf(routing.maxPriceCompletion?.toString() ?: "") }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = promptPriceText,
            onValueChange = {
                promptPriceText = it
                onChange(routing.copy(maxPricePrompt = it.toDoubleOrNull()))
            },
            label = { Text("Max $/1M prompt") },
            singleLine = true,
            trailingIcon = {
                if (promptPriceText.isNotEmpty()) {
                    IconButton(onClick = {
                        promptPriceText = ""
                        onChange(routing.copy(maxPricePrompt = null))
                    }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = completionPriceText,
            onValueChange = {
                completionPriceText = it
                onChange(routing.copy(maxPriceCompletion = it.toDoubleOrNull()))
            },
            label = { Text("Max $/1M completion") },
            singleLine = true,
            trailingIcon = {
                if (completionPriceText.isNotEmpty()) {
                    IconButton(onClick = {
                        completionPriceText = ""
                        onChange(routing.copy(maxPriceCompletion = null))
                    }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }

    OutlinedTextField(
        value = listToText(routing.quantizations),
        onValueChange = { onChange(routing.copy(quantizations = textToList(it))) },
        label = { Text("Quantizations (e.g. fp8, fp16)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RoutingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_caching))
        Switch(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) }
        )
    }

    if (provider.promptCaching) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ClaudePromptCacheTtl.entries.forEachIndexed { index, ttl ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ClaudePromptCacheTtl.entries.size
                    ),
                    label = {
                        Text(
                            when (ttl) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                                ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                            }
                        )
                    },
                    selected = provider.promptCacheTtl == ttl,
                    onClick = { onEdit(provider.copy(promptCacheTtl = ttl)) }
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        var keyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )
    }

    if (!provider.vertexAI) {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && (
                !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                ),
            supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                { Text("The base URL usually ends with `/v1beta`") }
            } else null,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_vertex_ai))
        Switch(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) }
        )
    }

    if (provider.vertexAI) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_provider_page_use_service_account))
            Switch(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) }
            )
        }
    }

    if (provider.vertexAI && provider.useServiceAccount) {
        OutlinedButton(
            onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setting_provider_page_import_service_account_json))
        }

        OutlinedTextField(
            value = provider.serviceAccountEmail,
            onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
            modifier = Modifier.fillMaxWidth(),
        )

        var privateKeyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.privateKey,
            onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_private_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
            minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { privateKeyVisible = !privateKeyVisible }) {
                    Icon(if (privateKeyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )

        OutlinedTextField(
            value = provider.location,
            onValueChange = { onEdit(provider.copy(location = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_location)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = provider.projectId,
            onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_project_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureAICore(
    provider: ProviderSetting.AICore,
    onEdit: (provider: ProviderSetting.AICore) -> Unit,
) {
    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(id = R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // Release-stage radio. PREVIEW pulls a higher-quality but more flappy build of Gemini
    // Nano; STABLE is the default. Spec details in
    // docs/superpowers/specs/2026-05-04-aicore-provider-design.md.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.setting_provider_aicore_release_stage), modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            val stages = me.rerere.ai.provider.AICoreReleaseStage.entries
            stages.forEachIndexed { index, stage ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = stages.size),
                    label = { Text(stage.name) },
                    selected = provider.releaseStage == stage,
                    onClick = { onEdit(provider.copy(releaseStage = stage)) },
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.setting_provider_aicore_status_help),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.ProviderConfigureLiteRT(
    provider: ProviderSetting.LiteRtLocal,
    onEdit: (ProviderSetting.LiteRtLocal) -> Unit,
) {
    val vm = koinViewModel<SettingLocalLlmViewModel>(
        key = "configure-${LocalRuntime.LiteRT.displayName}",
        parameters = { parametersOf(LocalRuntime.LiteRT) },
    )
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    val accelerator by vm.accelerator.collectAsStateWithLifecycle()
    val forceCpu by vm.forceCpu.collectAsStateWithLifecycle()
    val maxNumTokensOverride by vm.maxNumTokensOverride.collectAsStateWithLifecycle()
    val crashRecoveryAccel by vm.crashRecoveryAccelerator.collectAsStateWithLifecycle()
    val installedModelFiles by vm.installedModelFiles.collectAsStateWithLifecycle()
    val visionUnavailableSet by vm.visionUnavailableSet.collectAsStateWithLifecycle()
    val perfTelemetry by vm.perfTelemetry.collectAsStateWithLifecycle()

    provider.description()

    // Friendly post-crash banner. Default tone is "we handled it", not "panic".
    crashRecoveryAccel?.let { accel ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.dismissCrashRecovery() },
        ) {
            Text(
                text = stringResource(R.string.local_llm_crash_recovery_format, accel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(id = R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // Installed model count — model management is on the Models tab (page 1).
    Text(
        text = stringResource(R.string.local_llm_installed_models_count, provider.models.size),
        style = MaterialTheme.typography.bodySmall,
    )

    // URL install field — paste an HF URL, hit Install.
    var manualUrl by remember { mutableStateOf("") }
    OutlinedTextField(
        value = manualUrl,
        onValueChange = { manualUrl = it },
        label = { Text(stringResource(R.string.local_llm_install_url_label)) },
        supportingText = { Text(stringResource(R.string.local_llm_install_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                vm.startManualDownload(manualUrl)
                manualUrl = ""
            },
            enabled = manualUrl.isNotBlank() && downloadProgress == null,
        ) {
            Text(stringResource(R.string.local_llm_install_url_action))
        }
        OutlinedButton(
            onClick = { vm.startDefaultDownload() },
            enabled = downloadProgress == null,
        ) {
            Text(stringResource(R.string.local_llm_download_default))
        }
    }

    // Manage installed files — rename or delete each downloaded .litertlm.
    if (provider.models.isNotEmpty()) {
        Text(
            stringResource(R.string.local_llm_manage_files_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        provider.models.forEach { model ->
            InstalledModelRow(
                model = model,
                visionUnavailable = model.modelId in visionUnavailableSet,
                // After a native crash inside liblitertlm we suppress the Re-try
                // vision button: re-trying immediately is what just crashed the app.
                // The user dismisses the crash banner (above) when they want to opt
                // back in to taking that risk; the button reappears.
                allowVisionRetry = crashRecoveryAccel == null,
                perfSample = perfTelemetry[model.modelId],
                onRename = { newName -> vm.renameModel(model.modelId, newName) },
                onDelete = { vm.deleteModel(model.modelId) },
                onRetryVision = { vm.retryVisionEncoder(model.modelId) },
            )
        }
    }

    // Recommended-models curated picker. Sourced from Google AI Edge Gallery's allowlist
    // (LiteRtCatalog.ENTRIES). Per-entry Install button calls the same startManualDownload
    // path the URL-paste field uses, so the install flow is identical.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.local_llm_catalog_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.local_llm_catalog_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LiteRtCatalog.ENTRIES.forEach { entry ->
            LiteRtCatalogEntryCard(
                entry = entry,
                installed = entry.modelFile in installedModelFiles,
                downloadInProgress = downloadProgress != null,
                onInstall = { vm.startManualDownload(entry.resolveUrl()) },
            )
        }
    }

    // Accelerator row with re-detect button.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.local_llm_accelerator_label, accelerator ?: "auto"),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { vm.reDetectAccelerator() }) {
            Text(stringResource(R.string.local_llm_re_detect))
        }
    }

    // GPU acceleration toggle. The default is now device-dependent (see
    // LocalRuntimePreferences.defaultForceCpu): ON for capable devices, OFF only for the
    // Google Tensor crash class where LiteRT-LM 0.11.0's GPU/NNAPI backend SIGSEGVs during
    // inference. The toggle still lets the user override either way; the crash sweep and
    // the runtime's GPU->CPU fallback backstop a wrong default.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.local_llm_try_gpu_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.local_llm_try_gpu_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = !forceCpu,
            onCheckedChange = { wantGpu -> vm.setForceCpu(!wantGpu) },
        )
    }

    // Max-context override. Lets users push capable models (Gemma 4 E2B = 32k) past
    // Gallery's curated defaults — the model's underlying KV cache size is still the
    // hard ceiling (Qwen `ekv4096` rejects values above 4096 regardless of this).
    var maxTokensInput by remember(maxNumTokensOverride) {
        mutableStateOf(maxNumTokensOverride?.toString() ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.local_llm_max_tokens_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.local_llm_max_tokens_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokensInput,
                onValueChange = { newValue ->
                    // Accept digits-only input; empty string = use curated default.
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        maxTokensInput = newValue
                        val parsed = newValue.toIntOrNull()?.takeIf { it in 1..131072 }
                        vm.setMaxNumTokensOverride(parsed)
                    }
                },
                placeholder = {
                    Text(stringResource(R.string.local_llm_max_tokens_placeholder))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    maxTokensInput = ""
                    vm.setMaxNumTokensOverride(null)
                },
                enabled = maxNumTokensOverride != null,
            ) {
                Text(stringResource(R.string.local_llm_max_tokens_reset))
            }
        }
    }

    // Download progress indicator.
    downloadProgress?.let { progress ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (progress.totalBytes != null && progress.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.local_llm_download_progress, progress.percent),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    // Error text + optional "Delete model" action when a model file is the likely culprit.
    errorMessage?.let { msg ->
        Text(
            text = stringResource(R.string.local_llm_status_error_format, msg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        // If there are installed models, offer to delete them so the user can clear a
        // broken file (e.g. wrong runtime version) without navigating away.
        if (provider.models.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                provider.models.forEach { model ->
                    OutlinedButton(onClick = { vm.deleteModel(model.modelId) }) {
                        Text(
                            text = stringResource(R.string.local_llm_delete_model) +
                                if (provider.models.size > 1) " ${model.modelId}" else "",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiteRtCatalogEntryCard(
    entry: LiteRtCatalogEntry,
    installed: Boolean,
    downloadInProgress: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (entry.recommended) {
                    Text(
                        text = stringResource(R.string.local_llm_catalog_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (entry.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.tags.forEach { tag ->
                        val labelRes = when (tag) {
                            "multimodal" -> R.string.local_llm_catalog_tag_multimodal
                            "thinking" -> R.string.local_llm_catalog_tag_thinking
                            "speculative-decoding" -> R.string.local_llm_catalog_tag_speculative
                            else -> null
                        }
                        val label = labelRes?.let { stringResource(it) } ?: tag
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.surface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }

            Text(
                text = String.format(
                    java.util.Locale.US,
                    stringResource(R.string.local_llm_catalog_size_format),
                    entry.sizeBytes / 1_000_000_000.0,
                    entry.minDeviceMemoryGb,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (installed) {
                    Text(
                        text = stringResource(R.string.local_llm_catalog_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !downloadInProgress,
                    ) {
                        Text(stringResource(R.string.local_llm_catalog_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledModelRow(
    model: Model,
    visionUnavailable: Boolean,
    allowVisionRetry: Boolean,
    perfSample: me.rerere.locallm.LocalRuntimePreferences.PerfSample?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRetryVision: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember(model.id) { mutableStateOf(model.displayName) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (renaming) {
        OutlinedTextField(
            value = renameText,
            onValueChange = { renameText = it },
            label = { Text(stringResource(R.string.local_llm_rename_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = { renaming = false }) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    onRename(renameText)
                    renaming = false
                },
                enabled = renameText.isNotBlank() && renameText != model.displayName,
            ) {
                Text(stringResource(R.string.local_llm_rename_save))
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    model.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )
                if (visionUnavailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.local_llm_vision_unavailable_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (allowVisionRetry) {
                            TextButton(
                                onClick = onRetryVision,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    stringResource(R.string.local_llm_vision_retry),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                perfSample?.let { sample ->
                    Text(
                        text = stringResource(
                            R.string.local_llm_perf_telemetry_format,
                            sample.prefillTps,
                            sample.decodeTps,
                        ) + if (sample.specDecodingEngaged) " · " +
                            stringResource(R.string.local_llm_spec_decoding_engaged_short)
                        else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
            IconButton(onClick = { renaming = true }) {
                Icon(HugeIcons.Edit01, stringResource(R.string.local_llm_rename))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(HugeIcons.Delete01, stringResource(R.string.local_llm_delete))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.local_llm_delete_confirm_title)) },
            text = { Text(stringResource(R.string.local_llm_delete_confirm_message, model.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.local_llm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
