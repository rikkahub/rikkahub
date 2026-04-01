package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.normalizeStPresetState
import me.rerere.rikkahub.data.model.selectedStPreset
import me.rerere.rikkahub.data.model.upsertStPreset

internal data class PendingCharacterCardImport(
    val application: AssistantImportApplication,
    val runtimeTemplate: SillyTavernPromptTemplate,
)

internal fun AssistantImportPayload.characterRuntimeTemplate(): SillyTavernPromptTemplate {
    return presetTemplate ?: defaultSillyTavernPromptTemplate()
}

internal fun Settings.needsCharacterCardRuntimeActivation(): Boolean {
    return !stPresetEnabled || selectedStPreset() == null
}

internal fun Settings.enableCharacterCardRuntime(
    template: SillyTavernPromptTemplate = defaultSillyTavernPromptTemplate(),
): Settings {
    val seeded = if (selectedStPreset() == null) {
        upsertStPreset(
            preset = SillyTavernPreset(template = template),
            select = true,
        )
    } else {
        this
    }
    return seeded.copy(stPresetEnabled = true).normalizeStPresetState()
}

@Composable
internal fun CharacterCardRuntimeActivationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("启用 ST 运行时？")
        },
        text = {
            Text(
                "角色卡要参与生成，必须启用全局 ST 预设。现在启用后，如果还没有活动预设，会自动补一份默认预设并选中。" +
                    "若暂不启用，本次导入的角色卡只会被保存，聊天时不会生效。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("启用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不启用")
            }
        }
    )
}
