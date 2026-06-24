package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.ui.SegmentedButtonLabel
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    // The provider-TYPE selector (tabs) converts the in-flight provider via convertTo. That is only
    // meaningful while ADDING a new provider — switching the type of an ALREADY-SAVED provider would
    // rewrite its kind and drop type-specific config. So the tabs show only in the add flow; editing a
    // saved provider (detail page, model override) keeps its type fixed.
    allowTypeChange: Boolean = false,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        if (!provider.builtIn && allowTypeChange) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = { SegmentedButtonLabel(providerTypeLabel(type)) },
                        selected = provider::class == type,
                        onClick = { onEdit(provider.convertTo(type)) }
                    )
                }
            }
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
        }
    }
}

/**
 * The user-facing tab label for a provider subtype. The Kotlin class is still `Claude` (its
 * `@SerialName` stays "claude" for wire back-compat), but the brand is surfaced as "Anthropic";
 * everything else uses the class's own default display name.
 */
private fun providerTypeLabel(type: KClass<out ProviderSetting>): String = when (type) {
    ProviderSetting.Claude::class -> "Anthropic"
    else -> defaultProviderName(type)
}

/** The default `name` of a freshly-minted provider of [type] (its data-class default). */
private fun defaultProviderName(type: KClass<out ProviderSetting>): String = when (type) {
    ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().name
    ProviderSetting.Google::class -> ProviderSetting.Google().name
    ProviderSetting.Claude::class -> ProviderSetting.Claude().name
    else -> ""
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
    }
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)
    // Default-name fix: if the name is still the SOURCE subtype's default (the user never typed one),
    // adopt the TARGET subtype's default so switching tabs doesn't leave e.g. a Google provider named
    // "OpenAI". A name the user actually typed (≠ source default) is preserved verbatim.
    val convertedName = if (this.name == defaultProviderName(this::class)) {
        defaultProviderName(type)
    } else {
        this.name
    }

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = convertedName, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = convertedName, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = convertedName, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    // A ChatGPT-mode OpenAI provider's default base URL is the Codex backend, NOT api.openai.com.
    // Without this, "reset base URL" would point a ChatGPT provider at the Standard OpenAI host while
    // leaving mode = ChatGPT, so ChatGPTProvider would then build Codex requests against the wrong host.
    if (this is ProviderSetting.OpenAI && mode == OpenAIMode.ChatGPT) return CHATGPT_CODEX_BASE_URL
    // Azure's base URL is the user's resource endpoint — there is no shared default to reset to, so the
    // "default" IS whatever the user entered (reset is a no-op; the reset affordance hides itself).
    if (this is ProviderSetting.OpenAI && mode == OpenAIMode.Azure) return baseUrl
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
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
private const val CHATGPT_OFFICIAL_HOST = "chatgpt.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST,
    CHATGPT_OFFICIAL_HOST
)

/** The Codex backend root a ChatGPT-mode OpenAI provider talks to (same value the wire defaults to). */
private const val CHATGPT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"

/**
 * Switch an OpenAI provider's [OpenAIMode], re-pointing the base URL to the new mode's default ONLY
 * when it still holds the other mode's default (so a mode switch doesn't strand the Codex URL on the
 * Standard path, or vice-versa). A base URL the user customized is preserved.
 */
private fun ProviderSetting.OpenAI.switchOpenAIMode(target: OpenAIMode): ProviderSetting.OpenAI {
    if (mode == target) return this
    val standardDefault = ProviderSetting.OpenAI().baseUrl
    val newBaseUrl = when {
        target == OpenAIMode.ChatGPT && baseUrl == standardDefault -> CHATGPT_CODEX_BASE_URL
        target == OpenAIMode.Standard && baseUrl == CHATGPT_CODEX_BASE_URL -> standardDefault
        else -> baseUrl
    }
    return copy(mode = target, baseUrl = newBaseUrl)
}

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current

    ProviderDescription(provider)

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // Auth/transport mode (the OpenAI-brand analog of the Google provider's Vertex/Gagy modes):
    // Standard API key vs the ChatGPT (Codex) subscription backend. Hidden on a built-in provider.
    if (!provider.builtIn) {
        val modes = listOf(OpenAIMode.Standard, OpenAIMode.Azure, OpenAIMode.ChatGPT)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, m ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        SegmentedButtonLabel(
                            when (m) {
                                OpenAIMode.Standard -> "API Key"
                                OpenAIMode.Azure -> "Azure"
                                OpenAIMode.ChatGPT -> "ChatGPT"
                            }
                        )
                    },
                    selected = provider.mode == m,
                    onClick = { onEdit(provider.switchOpenAIMode(m)) }
                )
            }
        }
    }

    when (provider.mode) {
        OpenAIMode.Azure -> {
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
                placeholder = { Text("https://{resource}.openai.azure.com") },
                supportingText = { Text("Azure resource endpoint root. Each model's ID is its deployment name.") },
                modifier = Modifier.fillMaxWidth(),
                isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            )

            OutlinedTextField(
                value = provider.azureApiVersion,
                onValueChange = { onEdit(provider.copy(azureApiVersion = it.trim())) },
                label = { Text("API version") },
                placeholder = { Text("2024-10-21") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
        }

        OpenAIMode.ChatGPT -> {
            var tokenVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = provider.accessToken,
                onValueChange = { onEdit(provider.copy(accessToken = it.trim())) },
                label = { Text("Access Token") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
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
        }

        OpenAIMode.Standard -> {
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
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    ProviderDescription(provider)

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    val authTypes = listOf(ClaudeAuthType.ApiKey, ClaudeAuthType.OAuth)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        authTypes.forEachIndexed { index, authType ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = authTypes.size),
                label = {
                    SegmentedButtonLabel(
                        when (authType) {
                            ClaudeAuthType.ApiKey -> "API Key"
                            ClaudeAuthType.OAuth -> "OAuth Token"
                        }
                    )
                },
                selected = provider.authType == authType,
                onClick = { onEdit(provider.copy(authType = authType)) }
            )
        }
    }

    if (provider.authType == ClaudeAuthType.ApiKey) {
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
    } else {
        var tokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.oauthToken,
            onValueChange = { onEdit(provider.copy(oauthToken = it.trim())) },
            label = { Text("OAuth Access Token") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("1M context (Claude Max)")
            Switch(
                checked = provider.oauthContext1M,
                onCheckedChange = { onEdit(provider.copy(oauthContext1M = it)) }
            )
        }
    }

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

    ProviderDescription(provider)

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!provider.antigravity && !(provider.vertexAI && provider.useServiceAccount)) {
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

    if (!provider.antigravity && !provider.vertexAI) {
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

    if (provider.antigravity) {
        var tokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.antigravityRefreshToken,
            onValueChange = { onEdit(provider.copy(antigravityRefreshToken = it.trim())) },
            label = { Text("Antigravity refresh token") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
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
        Text("Antigravity")
        Switch(
            checked = provider.antigravity,
            onCheckedChange = {
                onEdit(provider.copy(antigravity = it, vertexAI = if (it) false else provider.vertexAI))
            }
        )
    }

    if (!provider.antigravity) {
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
