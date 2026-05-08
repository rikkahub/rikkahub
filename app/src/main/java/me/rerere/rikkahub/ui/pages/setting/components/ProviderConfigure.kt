package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.locallm.LocalRuntime
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        // Type
        if (!provider.builtIn) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = {
                            Text(type.simpleName ?: "")
                        },
                        selected = provider::class == type,
                        onClick = {
                            onEdit(provider.convertTo(type))
                        }
                    )
                }
            }
        }

        // [!] just for debugging
        // Text(JsonInstant.encodeToString(provider), fontSize = 10.sp)

        // Provider Configure
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

            is ProviderSetting.LlamaCppLocal -> {
                ProviderConfigureLlamaCpp(provider, onEdit)
            }
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) {
        return this
    }

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
        is ProviderSetting.AICore -> "" // on-device, no API key
        is ProviderSetting.LiteRtLocal -> "" // on-device, no API key
        is ProviderSetting.LlamaCppLocal -> "" // on-device, no API key
    }

    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.AICore -> "" // on-device, no base URL
        is ProviderSetting.LiteRtLocal -> "" // on-device, no base URL
        is ProviderSetting.LlamaCppLocal -> "" // on-device, no base URL
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
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
        )

        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
        )

        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
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
            is ProviderSetting.LlamaCppLocal -> return "" // on-device, no base URL
        }
    }

    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
        is ProviderSetting.AICore -> ""
        is ProviderSetting.LiteRtLocal -> ""
        is ProviderSetting.LlamaCppLocal -> ""
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
        is ProviderSetting.LlamaCppLocal -> this // no base URL to reset
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.AICore -> return true // no base URL concept
        is ProviderSetting.LiteRtLocal -> return true // no base URL concept
        is ProviderSetting.LlamaCppLocal -> return true // no base URL concept
    }
    return baseUrl == defaultBaseUrlForReset()
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) {
        return targetDefaultBaseUrl
    }

    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder()
        .encodedPath(convertedPath)
        .build()
        .toString()
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
    if (value.isEmpty() || value == "/") {
        return ""
    }
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
private fun ColumnScope.ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current

    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl()
    )

    if (!provider.useResponseApi) {
        OutlinedTextField(
            value = provider.chatCompletionsPath,
            onValueChange = {
                onEdit(provider.copy(chatCompletionsPath = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_path))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !provider.builtIn
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_response_api), modifier = Modifier.weight(1f))
        val responseAPIWarning = stringResource(id = R.string.setting_provider_page_response_api_warning)
        Checkbox(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))

                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(
                        message = responseAPIWarning,
                        type = ToastType.Warning
                    )
                }
            }
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl()
    )

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
}

@Composable
private fun ColumnScope.ProviderConfigureGoogle(
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
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }

    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_vertex_ai), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.vertexAI,
            onCheckedChange = {
                onEdit(provider.copy(vertexAI = it))
            }
        )
    }

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = {
                onEdit(provider.copy(apiKey = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_key))
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
    }

    if (!provider.vertexAI) {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = {
                onEdit(provider.copy(baseUrl = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && (
                !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
            ),
            supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                {
                    Text("The base URL usually ends with `/v1beta`")
                }
            } else null
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(id = R.string.setting_provider_page_use_service_account),
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = provider.useServiceAccount,
                onCheckedChange = {
                    onEdit(provider.copy(useServiceAccount = it))
                }
            )
        }

        if (provider.useServiceAccount) {
            OutlinedButton(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setting_provider_page_import_service_account_json))
            }
            OutlinedTextField(
                value = provider.serviceAccountEmail,
                onValueChange = {
                    onEdit(provider.copy(serviceAccountEmail = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_service_account_email))
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = provider.privateKey,
                onValueChange = {
                    onEdit(provider.copy(privateKey = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_private_key))
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            )
            OutlinedTextField(
                value = provider.location,
                onValueChange = {
                    onEdit(provider.copy(location = it.trim()))
                },
                label = {
                    // https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations#available-regions
                    Text(stringResource(id = R.string.setting_provider_page_location))
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = provider.projectId,
                onValueChange = {
                    onEdit(provider.copy(projectId = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_project_id))
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
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

    provider.description()

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
                text = "Downloading… ${progress.percent}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    // Error text with dismiss.
    errorMessage?.let { msg ->
        Text(
            text = stringResource(R.string.local_llm_status_error_format, msg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureLlamaCpp(
    provider: ProviderSetting.LlamaCppLocal,
    @Suppress("UNUSED_PARAMETER") onEdit: (ProviderSetting.LlamaCppLocal) -> Unit,
) {
    provider.description()

    Text(
        text = stringResource(R.string.local_llm_llamacpp_not_yet_implemented),
        style = MaterialTheme.typography.bodyMedium,
    )
}
