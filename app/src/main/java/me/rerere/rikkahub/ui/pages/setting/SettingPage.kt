package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.navigation.NavController
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Terminal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.utils.countChatFiles
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? ->
            if (uri != null) {
                vm.exportConversations(context, uri)
            } else {
                Toast.makeText(context, context.getString(R.string.export_conversations_cancelled), Toast.LENGTH_SHORT).show()
            }
        }
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior
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

                                navController.navigate("setting") {
                                    popUpTo("setting") {
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
                ListItem(
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
                    leadingContent = {
                        Icon(Lucide.Palette, null)
                    }
                )
            }

            if(!settings.dynamicColor) {
                item {
                    PresetThemeButtonGroup(
                        themeId = settings.themeId,
                        type = settings.themeType,
                        modifier = Modifier.fillMaxWidth(),
                        onChangeType = {
                            vm.updateSettings(settings.copy(themeType = it))
                        },
                        onChangeTheme = {
                            vm.updateSettings(settings.copy(themeId = it))
                        }
                    )
                }
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_display_setting)) },
                    description = { Text(stringResource(R.string.setting_page_display_setting_desc)) },
                    icon = { Icon(Lucide.Monitor, "Display Setting") },
                    link = "setting/display"
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_assistant)) },
                    description = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                    icon = { Icon(Lucide.Bot, "Assistant") },
                    link = "assistant"
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
                    icon = { Icon(Lucide.Heart, "Default Model") },
                    link = "setting/models"
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_providers)) },
                    description = { Text(stringResource(R.string.setting_page_providers_desc)) },
                    icon = { Icon(Lucide.Boxes, "Models") },
                    link = "setting/provider"
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_search_service)) },
                    description = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                    icon = { Icon(Lucide.Compass, "Search") },
                    link = "setting/search"
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text("MCP") },
                    description = { Text("配置MCP Servers") },
                    icon = { Icon(Lucide.Terminal, "MCP")},
                    link = "setting/mcp"
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
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_about)) },
                    description = { Text(stringResource(R.string.setting_page_about_desc)) },
                    icon = { Icon(Lucide.BadgeInfo, "About") },
                    link = "setting/about"
                )
            }

            item {
                val context = LocalContext.current
                val storageState by produceState(-1 to 0L) {
                    value = context.countChatFiles()
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
                )
            }

            item {
                SettingItem(
                    navController = navController,
                    title = { Text(stringResource(R.string.setting_page_export_conversations_title)) },
                    description = { Text(stringResource(R.string.setting_page_export_conversations_desc)) },
                    icon = { Icon(Lucide.Download, "Export Conversations") },
                    onClick = {
                        exportLauncher.launch("conversations-export.json")
                    }
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
private fun ProviderConfigWarningCard(navController: NavController) {
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
                    navController.navigate("setting/provider")
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

@Composable
fun SettingItem(
    navController: NavController,
    title: @Composable () -> Unit,
    description: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    link: String? = null,
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
            }
        )
    }
}