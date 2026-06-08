package me.rerere.rikkahub.ui.pages.setting.providerdetail

import androidx.compose.runtime.Composable
import me.rerere.ai.provider.ProviderSetting

@Composable
internal fun SettingProviderModelPage(
    provider: ProviderSetting,
    draft: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit
) {
    ModelList(
        providerSetting = provider,
        draft = draft,
        onUpdateProvider = onEdit
    )
}
