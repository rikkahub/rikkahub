package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Heading
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    // 正在编辑哪一项；编辑弹窗放在列表外面，CardGroup 里放不了
    var editing by remember { mutableStateOf<PromptSlot?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            // 只有翻译能单独设思考强度，所以自成一组
            CardGroup {
                promptItem(
                    slot = PromptSlot.TRANSLATION,
                    settings = settings,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    onClick = { editing = it },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                            onUpdateReasoningLevel = {
                                vm.updateSettings { latest -> latest.copy(translateThinkingBudget = it.budgetTokens) }
                            },
                        )
                    },
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                PromptSlot.entries.fastForEach { slot ->
                    if (slot != PromptSlot.TRANSLATION) {
                        promptItem(
                            slot = slot,
                            settings = settings,
                            iconContainerColor = containerColor,
                            iconContentColor = contentColor,
                            onClick = { editing = it },
                        )
                    }
                }
            }
        }
    }

    editing?.let { slot ->
        PromptEditSheet(
            title = stringResource(slot.title),
            description = stringResource(slot.variables),
            value = slot.get(settings),
            onValueChange = { value -> vm.updateSettings { latest -> slot.set(latest, value) } },
            onReset = { vm.updateSettings { latest -> slot.set(latest, slot.default) } },
            onDismiss = { editing = null },
        )
    }
}

// 可以自定义提示词的几项功能
private enum class PromptSlot(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    // 提示词里可用的变量说明
    @param:StringRes val variables: Int,
    val default: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
) {
    TRANSLATION(
        icon = HugeIcons.Translate,
        title = R.string.setting_model_page_prompt_translation,
        variables = R.string.setting_model_page_translate_prompt_vars,
        default = DEFAULT_TRANSLATION_PROMPT,
        get = { it.translatePrompt },
        set = { settings, value -> settings.copy(translatePrompt = value) },
    ),
    TITLE(
        icon = HugeIcons.Heading,
        title = R.string.setting_model_page_prompt_title,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_TITLE_PROMPT,
        get = { it.titlePrompt },
        set = { settings, value -> settings.copy(titlePrompt = value) },
    ),
    SUGGESTION(
        icon = HugeIcons.Idea01,
        title = R.string.setting_model_page_prompt_suggestion,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_SUGGESTION_PROMPT,
        get = { it.suggestionPrompt },
        set = { settings, value -> settings.copy(suggestionPrompt = value) },
    ),
    OCR(
        icon = HugeIcons.ScanText,
        title = R.string.setting_model_page_prompt_ocr,
        variables = R.string.setting_model_page_ocr_prompt_vars,
        default = DEFAULT_OCR_PROMPT,
        get = { it.ocrPrompt },
        set = { settings, value -> settings.copy(ocrPrompt = value) },
    ),
}

private val Whitespace = Regex("\\s+")

// 一项提示词：标题下面预览当前内容，点击后编辑
private fun CardGroupScope.promptItem(
    slot: PromptSlot,
    settings: Settings,
    iconContainerColor: Color,
    iconContentColor: Color,
    onClick: (PromptSlot) -> Unit,
) = item(
    onClick = { onClick(slot) },
    leadingContent = {
        CardGroupIcon(slot.icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
    headlineContent = { Text(stringResource(slot.title)) },
    supportingContent = {
        val prompt = slot.get(settings)
        Text(
            // 提示词里换行很多，压成一行才看得到实际内容
            text = remember(prompt) { prompt.trim().replace(Whitespace, " ") },
            // 只留一行：超过两行 ListItem 会把右侧箭头顶到上面去
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    trailingContent = {
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    },
)

@Composable
private fun PromptEditSheet(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 直接全展开，半展开时键盘会把输入框整个盖住
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                // 键盘弹出后高度不够时让输入框让位，下面的按钮不被挤出去
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                maxLines = 15,
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
        }
    }
}
