package me.rerere.rikkahub.ui.pages.quickstart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.tts.provider.TTSProviderSetting
import androidx.compose.ui.res.stringResource
import kotlin.uuid.Uuid

// 必要步骤只有提供商和默认模型，搜索和语音都有不用配置就能用的默认服务
private const val REQUIRED_STEPS = 2

@Composable
internal fun QuickStartGeneralTab(vm: QuickStartVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val readyProviders = settings.providers.filter { it.enabled && it.models.isNotEmpty() }
    val providerReady = readyProviders.isNotEmpty()
    val modelReady = settings.findModelById(settings.chatModelId) != null
    val doneSteps = listOf(providerReady, modelReady).count { it }

    val chatModelState = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuickStartHeroCard(
            title = stringResource(
                if (doneSteps == REQUIRED_STEPS) R.string.quick_start_page_ready_title else R.string.quick_start_page_setup_title
            ),
            description = stringResource(
                if (doneSteps == REQUIRED_STEPS) R.string.quick_start_page_ready_desc else R.string.quick_start_page_setup_desc
            ),
            icon = {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LinearWavyProgressIndicator(
                    progress = { doneSteps / REQUIRED_STEPS.toFloat() },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$doneSteps / $REQUIRED_STEPS",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
            if (doneSteps == REQUIRED_STEPS) {
                Button(
                    onClick = { navController.clearAndNavigate(Screen.Chat(Uuid.random().toString())) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.quick_start_page_start_chat))
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.quick_start_page_required_steps)) }) {
            stepItem(
                index = 1,
                title = R.string.quick_start_page_provider_title,
                description = R.string.quick_start_page_provider_desc,
                state = if (providerReady) StepState.DONE else StepState.TODO,
            ) {
                if (providerReady) {
                    Text(
                        text = stringResource(
                            R.string.quick_start_page_provider_summary,
                            readyProviders.size,
                            readyProviders.sumOf { it.models.size },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                StepActionButton(
                    text = stringResource(if (providerReady) R.string.quick_start_page_provider_manage else R.string.quick_start_page_provider_go),
                    primary = !providerReady,
                    onClick = { navController.navigate(Screen.SettingProvider) },
                )
            }

            stepItem(
                index = 2,
                title = R.string.quick_start_page_model_title,
                description = R.string.quick_start_page_model_desc,
                state = if (modelReady) StepState.DONE else StepState.TODO,
            ) {
                if (providerReady) {
                    ModelField(
                        model = chatModelState.currentModel,
                        onClick = { chatModelState.open() },
                        placeholder = stringResource(R.string.model_list_select_model),
                    )
                    TextButton(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.quick_start_page_model_more))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.quick_start_page_model_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.quick_start_page_optional_features)) }) {
            stepItem(
                index = 3,
                title = R.string.quick_start_page_search_title,
                description = R.string.quick_start_page_search_desc,
                state = StepState.OPTIONAL,
            ) {
                CurrentServiceRow(
                    current = currentSearchServiceName(settings),
                    onClick = { navController.navigate(Screen.SettingSearch) },
                )
            }

            stepItem(
                index = 4,
                title = R.string.quick_start_page_tts_title,
                description = R.string.quick_start_page_tts_desc,
                state = StepState.OPTIONAL,
            ) {
                CurrentServiceRow(
                    current = currentTTSName(settings),
                    onClick = { navController.navigate(Screen.SettingSpeech) },
                )
            }
        }
    }

    ModelListSheet(state = chatModelState) { vm.setDefaultChatModel(it) }
}

@Composable
private fun StepActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Text(text)
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp),
        )
    }
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun CurrentServiceRow(current: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_start_page_current_service, current),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onClick) {
            Text(stringResource(R.string.quick_start_page_service_settings))
        }
    }
}

@Composable
private fun currentSearchServiceName(settings: Settings): String =
    settings.searchServices.getOrNull(settings.searchServiceSelected)?.displayName
        ?: stringResource(R.string.quick_start_page_not_selected)

@Composable
private fun currentTTSName(settings: Settings): String = when (val provider = settings.getSelectedTTSProvider()) {
    null -> stringResource(R.string.quick_start_page_not_selected)
    is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
    else -> provider.name.ifBlank { stringResource(R.string.quick_start_page_unnamed) }
}
