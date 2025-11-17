package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var amoledDarkMode by rememberAmoledDarkMode()

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(
            settings.copy(
                displaySetting = setting
            )
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_display_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .consumeWindowInsets(contentPadding),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_theme_setting),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_dynamic_color))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_page_dynamic_color_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(dynamicColor = it))
                            },
                        )
                    },
                )
            }

            if (!settings.dynamicColor) {
                item {
                    PresetThemeButtonGroup(
                        themeId = settings.themeId,
                        modifier = Modifier.fillMaxWidth(),
                        onChangeTheme = {
                            vm.updateSettings(settings.copy(themeId = it))
                        }
                    )
                }
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = amoledDarkMode,
                            onCheckedChange = {
                                amoledDarkMode = it
                            }
                        )
                    },
                )
            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_basic_settings),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = createNewConversationOnStart,
                            onCheckedChange = {
                                createNewConversationOnStart = it
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_notification_message_generated))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_notification_message_generated_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableNotificationOnMessageGeneration,
                            onCheckedChange = {
                                if (it && !permissionState.allPermissionsGranted) {
                                    // 请求权限
                                    permissionState.requestPermissions()
                                }
                                updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                            }
                        )
                    },
                )
            }

//            item {
//                ListItem(
//                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
//                    headlineContent = {
//                        Text(stringResource(R.string.setting_display_page_developer_mode))
//                    },
//                    supportingContent = {
//                        Text(stringResource(R.string.setting_display_page_developer_mode_desc))
//                    },
//                    trailingContent = {
//                        Switch(
//                            checked = settings.developerMode,
//                            onCheckedChange = {
//                                vm.updateSettings(settings.copy(developerMode = it))
//                            }
//                        )
//                    },
//                )
//            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_chat_settings),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_show_user_avatar_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_show_user_avatar_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showUserAvatar,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_show_model_name_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_show_model_name_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showModelName,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showModelName = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_show_message_jumper_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_show_message_jumper_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showMessageJumper,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                            }
                        )
                    },
                )
            }

            if (displaySetting.showMessageJumper) {
                item {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_message_jumper_position_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_message_jumper_position_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.messageJumperOnLeft,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableMessageGenerationHapticEffect,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_skip_crop_image_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_skip_crop_image_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.skipCropImage,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.codeBlockAutoWrap,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.codeBlockAutoCollapse,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                            }
                        )
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_font_size_title))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = displaySetting.fontSizeRatio,
                        onValueChange = {
                            updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                        },
                        valueRange = 0.5f..2f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(displaySetting.fontSizeRatio * 100).toInt()}%",
                    )
                }
                MarkdownBlock(
                    content = stringResource(R.string.setting_display_page_font_size_preview),
                    modifier = Modifier.padding(8.dp),
                    style = LocalTextStyle.current.copy(
                        fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                        lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                    )
                )
            }
        }
    }
}
