package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.pages.setting.components.CustomThemeSection
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalThemeTokenOverrides
import me.rerere.rikkahub.ui.theme.themedRoundedShape
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

private const val MAX_CODE_BLOCK_RENDER_DEPTH = 100
private val AmoledBackground = Color(0xFF000000)

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    var amoledDarkMode by rememberAmoledDarkMode()
    val context = LocalContext.current

    val defaultLightScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        else -> findPresetTheme(settings.themeId).getColorScheme(dark = false)
    }
    val defaultDarkScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicDarkColorScheme(context)
        else -> findPresetTheme(settings.themeId).getColorScheme(dark = true)
    }.let { scheme ->
        if (amoledDarkMode) {
            scheme.copy(
                background = AmoledBackground,
                surface = AmoledBackground,
            )
        } else {
            scheme
        }
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        vm.updateDisplaySetting(setting)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    val themeTokens = LocalThemeTokenOverrides.current
    val settingsPanelShape = themeTokens.themedRoundedShape(
        tokenKey = "shapeLarge",
        fallback = 20.dp,
    )
    PermissionManager(permissionState = permissionState)

    fun ensureNotificationPermissionIfNeeded(enabled: Boolean) {
        if (enabled && !permissionState.allPermissionsGranted) {
            permissionState.requestPermissions()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_display_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_theme_setting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                    )
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 4.dp
                                )
                            ),
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                            )
                        },
                        colors = CustomColors.listItemColors,
                    )
                    if (!settings.dynamicColor) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright)
                        ) {
                            PresetThemeButtonGroup(
                                themeId = settings.themeId,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeTheme = { vm.updateSettings(settings.copy(themeId = it)) }
                            )
                        }
                    }
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 4.dp,
                                    topEnd = 4.dp,
                                    bottomStart = 20.dp,
                                    bottomEnd = 20.dp
                                )
                            ),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                        colors = CustomColors.listItemColors,
                    )
                }
            }

            item {
                CustomThemeSection(
                    value = settings.customThemeSetting,
                    defaultLightScheme = defaultLightScheme,
                    defaultDarkScheme = defaultDarkScheme,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onUpdate = { customThemeSetting ->
                        vm.updateSettings(settings.copy(customThemeSetting = customThemeSetting))
                    },
                )
            }

            item(
                key = "general_settings_${settings.init}_${displaySetting.enableNotificationOnMessageGeneration}"
            ) {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        trailingContent = {
                            Switch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = { createNewConversationOnStart = it }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_updates_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUpdates,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showUpdates = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    ensureNotificationPermissionIfNeeded(it)
                                    updateDisplaySetting(
                                        displaySetting.copy(enableNotificationOnMessageGeneration = it)
                                    )
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tool_approval_notification)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tool_approval_notification_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableToolApprovalNotification,
                                onCheckedChange = {
                                    ensureNotificationPermissionIfNeeded(it)
                                    updateDisplaySetting(
                                        displaySetting.copy(enableToolApprovalNotification = it)
                                    )
                                }
                            )
                        },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(
                                            displaySetting.copy(enableLiveUpdateNotification = it)
                                        )
                                    }
                                )
                            },
                        )
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                    ) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showUserAvatar,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showAssistantBubble,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showAssistantBubble = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showModelIcon,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showModelName,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showModelName = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showDateBelowName,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showDateBelowName = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showTokenUsage,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showThinkingContent,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showThinkingContent = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.autoCloseThinking,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLatexRendering,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLatexRendering = it))
                                    }
                                )
                            },
                        )
                    }
                    val chatFontFamilyOptions = listOf(
                        ChatFontFamily.DEFAULT to stringResource(R.string.setting_display_page_chat_font_family_default),
                        ChatFontFamily.SERIF to stringResource(R.string.setting_display_page_chat_font_family_serif),
                        ChatFontFamily.MONOSPACE to stringResource(R.string.setting_display_page_chat_font_family_monospace),
                    )
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .fillMaxWidth()
                            .clip(settingsPanelShape)
                            .background(MaterialTheme.colorScheme.surfaceBright)
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                            colors = CustomColors.listItemColors,
                        )
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            chatFontFamilyOptions.forEachIndexed { index, (family, label) ->
                                SegmentedButton(
                                    selected = displaySetting.chatFontFamily == family,
                                    onClick = { updateDisplaySetting(displaySetting.copy(chatFontFamily = family)) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index,
                                        chatFontFamilyOptions.size
                                    ),
                                ) {
                                    Text(
                                        text = label,
                                        fontFamily = when (family) {
                                            ChatFontFamily.DEFAULT -> FontFamily.Default
                                            ChatFontFamily.SERIF -> FontFamily.Serif
                                            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
                                        }
                                    )
                                }
                            }
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                            colors = CustomColors.listItemColors,
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
                                fontFamily = when (displaySetting.chatFontFamily) {
                                    ChatFontFamily.DEFAULT -> FontFamily.Default
                                    ChatFontFamily.SERIF -> FontFamily.Serif
                                    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
                                }
                            )
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showLineNumbers = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .clip(settingsPanelShape)
                        .background(MaterialTheme.colorScheme.surfaceBright)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_render_depth_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_render_depth_desc)) },
                        colors = CustomColors.listItemColors,
                    )
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Slider(
                            value = displaySetting.codeBlockRenderMaxDepth
                                .coerceIn(0, MAX_CODE_BLOCK_RENDER_DEPTH)
                                .toFloat(),
                            onValueChange = {
                                updateDisplaySetting(displaySetting.copy(codeBlockRenderMaxDepth = it.toInt()))
                            },
                            valueRange = 0f..MAX_CODE_BLOCK_RENDER_DEPTH.toFloat(),
                            steps = MAX_CODE_BLOCK_RENDER_DEPTH - 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (displaySetting.codeBlockRenderMaxDepth > 0) {
                                displaySetting.codeBlockRenderMaxDepth.toString()
                            } else {
                                stringResource(R.string.setting_common_no_limit)
                            },
                        )
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_interaction_notification_settings)) },
                    ) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.sendOnEnter,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showMessageJumper,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                    }
                                )
                            },
                        )
                        if (displaySetting.showMessageJumper) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                                supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
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
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableAutoScroll,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.useAppIconStyleLoadingIndicator,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableBlurEffect,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableMessageGenerationHapticEffect,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.skipCropImage,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.pasteLongTextAsFile,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                    }
                                )
                            },
                        )
                    }

                    if (displaySetting.pasteLongTextAsFile) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxWidth()
                                .clip(settingsPanelShape)
                                .background(MaterialTheme.colorScheme.surfaceBright)
                        ) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                                colors = CustomColors.listItemColors,
                            )
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = displaySetting.pasteLongTextThreshold.toFloat(),
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                    },
                                    valueRange = 100f..10000f,
                                    steps = 98,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${displaySetting.pasteLongTextThreshold}",
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
