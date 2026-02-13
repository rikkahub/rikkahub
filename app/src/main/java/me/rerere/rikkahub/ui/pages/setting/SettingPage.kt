package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Drama
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.FolderOpen
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    if(settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(Lucide.Hammer, "Developer")
                        }
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_general_settings),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item("colorMode") {
                var colorMode by rememberColorMode()
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_color_mode))
                    },
                    leadingContent = {
                        Icon(Lucide.SunMoon, null)
                    },
                    trailingContent = {
                        Select(
                            options = ColorMode.entries,
                            selectedOption = colorMode,
                            onOptionSelected = {
                                colorMode = it
                                navController.navigate(Screen.Setting) {
                                    launchSingleTop = true
                                    popUpTo(Screen.Setting) {
                                        inclusive = true
                                    }
                                }
                            },
                            optionToString = {
                                when (it) {
                                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                }
                            },
                            modifier = Modifier.width(150.dp)
                        )
                    }
                )
            }


            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_display_setting)) },
                    description = { Text(stringResource(R.string.setting_page_display_setting_desc)) },
                    icon = { Icon(Lucide.Monitor, "Display Setting") },
                    link = Screen.SettingDisplay
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_assistant)) },
                    description = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                    icon = { Icon(Lucide.Drama, "Assistant") },
                    link = Screen.Assistant
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_prompts_title)) },
                    description = { Text(stringResource(R.string.setting_page_prompts_desc)) },
                    icon = { Icon(Lucide.BookOpen, "Prompts") },
                    link = Screen.Prompts
                )
            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_model_and_services),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_default_model)) },
                    description = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                    icon = { Icon(Lucide.Hammer, stringResource(R.string.setting_page_default_model)) },
                    link = Screen.SettingModels
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_providers)) },
                    description = { Text(stringResource(R.string.setting_page_providers_desc)) },
                    icon = { Icon(Lucide.Boxes, "Models") },
                    link = Screen.SettingProvider
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_search_service)) },
                    description = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                    icon = { Icon(Lucide.Earth, "Search") },
                    link = Screen.SettingSearch
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_tts_service)) },
                    description = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                    icon = { Icon(Lucide.Volume2, "TTS") },
                    link = Screen.SettingTTS
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_mcp)) },
                    description = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                    icon = { Icon(Lucide.Terminal, "MCP") },
                    link = Screen.SettingMcp
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_web_server)) },
                    description = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                    icon = { Icon(Lucide.Globe, "Web Server") },
                    link = Screen.SettingWeb
                )
            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_data_settings),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_data_backup)) },
                    description = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                    icon = { Icon(Lucide.Database, "Backup") },
                    link = Screen.Backup
                )
            }

            item {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    description = {
                        if (storageState.first == -1) {
                            Text(stringResource(R.string.calculating))
                        } else {
                            Text(
                                stringResource(
                                    R.string.setting_page_chat_storage_desc,
                                    storageState.first,
                                    storageState.second / 1024 / 1024.0
                                )
                            )
                        }
                    },
                    icon = {
                        Icon(Lucide.HardDrive, "Storage")
                    },
                    link = Screen.SettingFiles,
                )
            }

            stickyHeader {
                Text(
                    text = stringResource(R.string.setting_page_about),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                val context = LocalContext.current
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_about)) },
                    description = { Text(stringResource(R.string.setting_page_about_desc)) },
                    icon = { Icon(Lucide.BadgeInfo, "About") },
                    link = Screen.SettingAbout,
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    context.joinQQGroup("wMdqlDETtzIz6o49HrBR2TeQlwcX6RH9")
                                }
                            ) {
                                Icon(
                                    imageVector = TencentQQIcon,
                                    contentDescription = "QQ",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }

                            IconButton(
                                onClick = {
                                    context.openUrl("https://discord.gg/9weBqxe5c4")
                                }
                            ) {
                                Icon(
                                    imageVector = DiscordIcon,
                                    contentDescription = "Discord",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                )
            }

            item {
                val context = LocalContext.current
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_documentation)) },
                    description = { Text(stringResource(R.string.setting_page_documentation_desc)) },
                    icon = { Icon(Lucide.Library, stringResource(R.string.setting_page_documentation)) },
                    onClick = {
                        context.openUrl("https://docs.rikka-ai.com/docs/basic/get-started")
                    }
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_request_logs)) },
                    description = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                    icon = { Icon(Lucide.ScrollText, stringResource(R.string.setting_page_request_logs)) },
                    link = Screen.Log
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_donate)) },
                    description = {
                        Text(stringResource(R.string.setting_page_donate_desc))
                    },
                    icon = {
                        Icon(Lucide.Heart, "Donate")
                    },
                    link = Screen.SettingDonate
                )
            }

            item {
                val context = LocalContext.current
                val shareText = stringResource(R.string.setting_page_share_text)
                val share = stringResource(R.string.setting_page_share)
                val noShareApp = stringResource(R.string.setting_page_no_share_app)
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_share)) },
                    description = {
                        Text(stringResource(R.string.setting_page_share_desc))
                    },
                    icon = {
                        Icon(Lucide.Share2, "Share")
                    },
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "text/plain"
                        intent.putExtra(Intent.EXTRA_TEXT, shareText)
                        try {
                            context.startActivity(Intent.createChooser(intent, share))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: NavHostController) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(Lucide.MessageCircleWarning, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

@Composable
fun SettingItem(
    navController: NavHostController,
    title: @Composable () -> Unit,
    description: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    link: Screen? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = {
            if (link != null) navController.navigate(link)
            onClick()
        }
    ) {
        ListItem(
            headlineContent = {
                title()
            },
            supportingContent = {
                description()
            },
            leadingContent = {
                icon()
            },
            trailingContent = trailingContent
        )
    }
}
