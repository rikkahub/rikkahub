package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PromptCard(
                icon = { Icon(HugeIcons.Earth, null) },
                title = { Text(stringResource(R.string.setting_model_page_prompt_translation), maxLines = 1) },
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_model_page_prompt)) },
                    description = { Text(stringResource(R.string.setting_model_page_translate_prompt_vars)) }
                ) {
                    OutlinedTextField(
                        value = settings.translatePrompt,
                        onValueChange = { vm.updateSettings(settings.copy(translatePrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT))
                    }) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
                FormItem(
                    label = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                ) {
                    ReasoningButton(
                        reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        onUpdateReasoningLevel = {
                            vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens))
                        }
                    )
                }
            }
        }
        item {
            PromptCard(
                icon = { Icon(HugeIcons.Notebook01, null) },
                title = { Text(stringResource(R.string.setting_model_page_prompt_title), maxLines = 1) },
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_model_page_prompt)) },
                    description = { Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars)) }
                ) {
                    OutlinedTextField(
                        value = settings.titlePrompt,
                        onValueChange = { vm.updateSettings(settings.copy(titlePrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT))
                    }) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
        item {
            PromptCard(
                icon = { Icon(HugeIcons.MessageMultiple01, null) },
                title = { Text(stringResource(R.string.setting_model_page_prompt_suggestion), maxLines = 1) },
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_model_page_prompt)) },
                    description = { Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars)) }
                ) {
                    OutlinedTextField(
                        value = settings.suggestionPrompt,
                        onValueChange = { vm.updateSettings(settings.copy(suggestionPrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT))
                    }) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
        item {
            PromptCard(
                icon = { Icon(HugeIcons.View, null) },
                title = { Text(stringResource(R.string.setting_model_page_prompt_ocr), maxLines = 1) },
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_model_page_prompt)) },
                    description = { Text(stringResource(R.string.setting_model_page_ocr_prompt_vars)) }
                ) {
                    OutlinedTextField(
                        value = settings.ocrPrompt,
                        onValueChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT))
                    }) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
        item {
            PromptCard(
                icon = { Icon(HugeIcons.FileZip, null) },
                title = { Text(stringResource(R.string.setting_model_page_prompt_compress), maxLines = 1) },
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_model_page_prompt)) },
                    description = { Text(stringResource(R.string.setting_model_page_compress_prompt_vars)) }
                ) {
                    OutlinedTextField(
                        value = settings.compressPrompt,
                        onValueChange = { vm.updateSettings(settings.copy(compressPrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT))
                    }) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
                ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                    title()
                }
            }
            content()
        }
    }
}
