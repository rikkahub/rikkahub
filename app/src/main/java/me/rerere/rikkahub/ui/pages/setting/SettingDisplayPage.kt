package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(
            settings.copy(
                displaySetting = setting
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_display_page_title))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp)
        ) {
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showModelIcon,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_show_token_usage_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_show_token_usage_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showTokenUsage,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.autoCloseThinking,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                            }
                        )
                    },
                )
            }
            
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_limit_reasoning_height_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_limit_reasoning_height_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.limitReasoningHeightDuringLoading,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(limitReasoningHeightDuringLoading = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_show_updates_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_show_updates_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showUpdates,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showUpdates = it))
                            }
                        )
                    },
                )
            }
        }
    }
}