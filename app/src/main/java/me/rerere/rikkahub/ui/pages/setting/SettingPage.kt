package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.APP_README_URL
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.LuneBackdrop
import me.rerere.rikkahub.ui.components.ui.LuneSection
import me.rerere.rikkahub.ui.components.ui.LuneTopBarSurface
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
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
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val shareText = stringResource(R.string.setting_page_share_text)
    val share = stringResource(R.string.setting_page_share)
    val noShareApp = stringResource(R.string.setting_page_no_share_app)
    val enableGlassBlur = settings.displaySetting.enableBlurEffect && !listState.isScrollInProgress
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim()
    val warningTitle = stringResource(R.string.setting_page_config_api_title)
    val warningDesc = stringResource(R.string.setting_page_config_api_desc)

    fun matchesSetting(
        title: String,
        supporting: String = "",
        keywords: List<String> = emptyList(),
    ): Boolean {
        if (normalizedQuery.isBlank()) return true
        return buildList {
            add(title)
            if (supporting.isNotBlank()) add(supporting)
            addAll(keywords)
        }.any { candidate ->
            candidate.contains(normalizedQuery, ignoreCase = true)
        }
    }

    val generalSectionTitle = stringResource(R.string.setting_page_general_settings)
    val modelSectionTitle = stringResource(R.string.setting_page_model_and_services)
    val dataSectionTitle = stringResource(R.string.setting_page_data_settings)
    val aboutSectionTitle = stringResource(R.string.setting_page_about)

    val generalSectionMatch = matchesSetting(generalSectionTitle)
    val modelSectionMatch = matchesSetting(modelSectionTitle)
    val dataSectionMatch = matchesSetting(dataSectionTitle)
    val aboutSectionMatch = matchesSetting(aboutSectionTitle)

    Box(modifier = Modifier.fillMaxSize()) {
        LuneBackdrop()
        Scaffold(
            topBar = {
                LuneTopBarSurface(
                    hazeState = if (enableGlassBlur) hazeState else null,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    LargeFlexibleTopAppBar(
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
                                    Icon(HugeIcons.Developer, "Developer")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                        )
                    )
                }
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (enableGlassBlur) {
                            Modifier.hazeSource(state = hazeState)
                        } else {
                            Modifier
                        }
                    ),
                state = listState,
                contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (settings.isNotConfigured()) {
                    if (matchesSetting(warningTitle, warningDesc, listOf("provider", "api", "model"))) {
                        item {
                            ProviderConfigWarningCard(navController)
                        }
                    }
                }

                item("settingsSearch") {
                    LuneSection(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            singleLine = true,
                            placeholder = { Text("搜索设置") },
                            leadingIcon = {
                                Icon(HugeIcons.GlobalSearch, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(HugeIcons.Cancel01, contentDescription = null)
                                    }
                                }
                            },
                        )
                    }
                }

                item("generalSettings") {
                    var colorMode by rememberColorMode()
                    val colorModeTitle = stringResource(R.string.setting_page_color_mode)
                    val selectedColorModeText = when (colorMode) {
                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                    }
                    val displayTitle = stringResource(R.string.setting_page_display_setting)
                    val displayDesc = stringResource(R.string.setting_page_display_setting_desc)
                    val assistantTitle = stringResource(R.string.setting_page_assistant)
                    val assistantDesc = stringResource(R.string.setting_page_assistant_desc)
                    val scheduledTitle = stringResource(R.string.setting_page_scheduled_tasks)
                    val scheduledDesc = stringResource(R.string.setting_page_scheduled_tasks_desc)
                    val extensionsTitle = stringResource(R.string.setting_page_extensions)
                    val extensionsDesc = stringResource(R.string.setting_page_extensions_desc)
                    val showColorMode = generalSectionMatch || matchesSetting(colorModeTitle, selectedColorModeText)
                    val showDisplay = generalSectionMatch || matchesSetting(displayTitle, displayDesc)
                    val showAssistant = generalSectionMatch || matchesSetting(assistantTitle, assistantDesc)
                    val showScheduled = generalSectionMatch || matchesSetting(scheduledTitle, scheduledDesc)
                    val showExtensions = generalSectionMatch || matchesSetting(extensionsTitle, extensionsDesc)

                    if (showColorMode || showDisplay || showAssistant || showScheduled || showExtensions) {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            title = { Text(generalSectionTitle) },
                        ) {
                            if (showColorMode) {
                                item(
                                    leadingContent = { Icon(HugeIcons.Sun01, null) },
                                    trailingContent = {
                                        Select(
                                            options = ColorMode.entries,
                                            selectedOption = colorMode,
                                            onOptionSelected = {
                                                colorMode = it
                                                navController.navigate(Screen.Setting) {
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
                                    },
                                    headlineContent = { Text(colorModeTitle) },
                                    supportingContent = { Text(selectedColorModeText) },
                                )
                            }
                            if (showDisplay) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingDisplay) },
                                    leadingContent = { Icon(HugeIcons.Settings03, null) },
                                    supportingContent = { Text(displayDesc) },
                                    headlineContent = { Text(displayTitle) },
                                )
                            }
                            if (showAssistant) {
                                item(
                                    onClick = { navController.navigate(Screen.Assistant) },
                                    leadingContent = { Icon(HugeIcons.LookTop, null) },
                                    supportingContent = { Text(assistantDesc) },
                                    headlineContent = { Text(assistantTitle) },
                                )
                            }
                            if (showScheduled) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingScheduledTasks) },
                                    leadingContent = { Icon(Lucide.Clock, null) },
                                    supportingContent = { Text(scheduledDesc) },
                                    headlineContent = { Text(scheduledTitle) },
                                )
                            }
                            if (showExtensions) {
                                item(
                                    onClick = { navController.navigate(Screen.Extensions) },
                                    leadingContent = { Icon(HugeIcons.Package, null) },
                                    supportingContent = { Text(extensionsDesc) },
                                    headlineContent = { Text(extensionsTitle) },
                                )
                            }
                        }
                    }
                }

                item("modelServices") {
                    val defaultModelTitle = stringResource(R.string.setting_page_default_model)
                    val defaultModelDesc = stringResource(R.string.setting_page_default_model_desc)
                    val providersTitle = stringResource(R.string.setting_page_providers)
                    val providersDesc = stringResource(R.string.setting_page_providers_desc)
                    val searchTitle = stringResource(R.string.setting_page_search_service)
                    val searchDesc = stringResource(R.string.setting_page_search_service_desc)
                    val ttsTitle = stringResource(R.string.setting_page_tts_service)
                    val ttsDesc = stringResource(R.string.setting_page_tts_service_desc)
                    val mcpTitle = stringResource(R.string.setting_page_mcp)
                    val mcpDesc = stringResource(R.string.setting_page_mcp_desc)
                    val termuxTitle = stringResource(R.string.setting_page_termux)
                    val termuxDesc = stringResource(R.string.setting_page_termux_desc)
                    val integrationTitle = stringResource(R.string.setting_android_integration)
                    val integrationDesc = stringResource(R.string.setting_android_integration_desc)
                    val webTitle = stringResource(R.string.setting_page_web_server)
                    val webDesc = stringResource(R.string.setting_page_web_server_desc)

                    val showDefaultModel = modelSectionMatch || matchesSetting(defaultModelTitle, defaultModelDesc)
                    val showProviders = modelSectionMatch || matchesSetting(providersTitle, providersDesc, listOf("api", "provider", "model"))
                    val showSearch = modelSectionMatch || matchesSetting(searchTitle, searchDesc, listOf("web", "search"))
                    val showTts = modelSectionMatch || matchesSetting(ttsTitle, ttsDesc, listOf("voice", "audio"))
                    val showMcp = modelSectionMatch || matchesSetting(mcpTitle, mcpDesc, listOf("tool", "server"))
                    val showTermux = modelSectionMatch || matchesSetting(termuxTitle, termuxDesc, listOf("shell", "command"))
                    val showIntegration = modelSectionMatch || matchesSetting(integrationTitle, integrationDesc, listOf("android", "selection"))
                    val showWeb = modelSectionMatch || matchesSetting(webTitle, webDesc, listOf("web", "server"))

                    if (showDefaultModel || showProviders || showSearch || showTts || showMcp || showTermux || showIntegration || showWeb) {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            title = { Text(modelSectionTitle) },
                        ) {
                            if (showDefaultModel) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingModels) },
                                    leadingContent = { Icon(HugeIcons.AiMagic, null) },
                                    supportingContent = { Text(defaultModelDesc) },
                                    headlineContent = { Text(defaultModelTitle) },
                                )
                            }
                            if (showProviders) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingProvider) },
                                    leadingContent = { Icon(HugeIcons.Brain02, null) },
                                    supportingContent = { Text(providersDesc) },
                                    headlineContent = { Text(providersTitle) },
                                )
                            }
                            if (showSearch) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingSearch) },
                                    leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                                    supportingContent = { Text(searchDesc) },
                                    headlineContent = { Text(searchTitle) },
                                )
                            }
                            if (showTts) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingTTS) },
                                    leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                                    supportingContent = { Text(ttsDesc) },
                                    headlineContent = { Text(ttsTitle) },
                                )
                            }
                            if (showMcp) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingMcp) },
                                    leadingContent = { Icon(HugeIcons.McpServer, null) },
                                    supportingContent = { Text(mcpDesc) },
                                    headlineContent = { Text(mcpTitle) },
                                )
                            }
                            if (showTermux) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingTermux) },
                                    leadingContent = { Icon(Lucide.FolderOpen, null) },
                                    supportingContent = { Text(termuxDesc) },
                                    headlineContent = { Text(termuxTitle) },
                                )
                            }
                            if (showIntegration) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingAndroidIntegration) },
                                    leadingContent = { Icon(HugeIcons.TextSelection, null) },
                                    supportingContent = { Text(integrationDesc) },
                                    headlineContent = { Text(integrationTitle) },
                                )
                            }
                            if (showWeb) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingWeb) },
                                    leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                                    supportingContent = { Text(webDesc) },
                                    headlineContent = { Text(webTitle) },
                                )
                            }
                        }
                    }
                }

                item("dataSettings") {
                    val storageState by produceState(-1 to 0L) {
                        value = filesManager.countChatFiles()
                    }
                    val backupTitle = stringResource(R.string.setting_page_data_backup)
                    val backupDesc = stringResource(R.string.setting_page_data_backup_desc)
                    val historyTitle = stringResource(R.string.history_page_title)
                    val historyDesc = stringResource(R.string.history_page_search_messages)
                    val favoriteTitle = stringResource(R.string.favorite_page_title)
                    val filesTitle = stringResource(R.string.setting_page_chat_storage)
                    val filesDesc = if (storageState.first == -1) {
                        stringResource(R.string.calculating)
                    } else {
                        stringResource(
                            R.string.setting_page_chat_storage_desc,
                            storageState.first,
                            storageState.second / 1024 / 1024.0
                        )
                    }

                    val showBackup = dataSectionMatch || matchesSetting(backupTitle, backupDesc)
                    val showHistory = dataSectionMatch || matchesSetting(historyTitle, historyDesc, listOf("chat", "conversation"))
                    val showFavorite = dataSectionMatch || matchesSetting(favoriteTitle, favoriteTitle, listOf("star", "bookmark"))
                    val showFiles = dataSectionMatch || matchesSetting(filesTitle, filesDesc, listOf("storage", "image", "file"))

                    if (showBackup || showHistory || showFavorite || showFiles) {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            title = { Text(dataSectionTitle) },
                        ) {
                            if (showBackup) {
                                item(
                                    onClick = { navController.navigate(Screen.Backup) },
                                    leadingContent = { Icon(HugeIcons.Database02, null) },
                                    supportingContent = { Text(backupDesc) },
                                    headlineContent = { Text(backupTitle) },
                                )
                            }
                            if (showHistory) {
                                item(
                                    onClick = { navController.navigate(Screen.History) },
                                    leadingContent = { Icon(HugeIcons.TransactionHistory, null) },
                                    supportingContent = { Text(historyDesc) },
                                    headlineContent = { Text(historyTitle) },
                                )
                            }
                            if (showFavorite) {
                                item(
                                    onClick = { navController.navigate(Screen.Favorite) },
                                    leadingContent = { Icon(HugeIcons.Book01, null) },
                                    supportingContent = { Text(favoriteTitle) },
                                    headlineContent = { Text(favoriteTitle) },
                                )
                            }
                            if (showFiles) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingFiles) },
                                    leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                                    supportingContent = { Text(filesDesc) },
                                    headlineContent = { Text(filesTitle) },
                                )
                            }
                        }
                    }
                }

                item("aboutSettings") {
                    val aboutTitle = stringResource(R.string.setting_page_about)
                    val aboutDesc = stringResource(R.string.setting_page_about_desc)
                    val documentationTitle = stringResource(R.string.setting_page_documentation)
                    val documentationDesc = stringResource(R.string.setting_page_documentation_desc)
                    val logsTitle = stringResource(R.string.setting_page_request_logs)
                    val logsDesc = stringResource(R.string.setting_page_request_logs_desc)
                    val shareTitle = stringResource(R.string.setting_page_share)
                    val shareDesc = stringResource(R.string.setting_page_share_desc)
                    val donateTitle = stringResource(R.string.setting_page_donate)
                    val donateDesc = stringResource(R.string.setting_page_donate_desc)

                    val showAbout = aboutSectionMatch || matchesSetting(aboutTitle, aboutDesc)
                    val showDocumentation = aboutSectionMatch || matchesSetting(documentationTitle, documentationDesc, listOf("docs", "readme"))
                    val showLogs = aboutSectionMatch || matchesSetting(logsTitle, logsDesc, listOf("debug", "request"))
                    val showShare = aboutSectionMatch || matchesSetting(shareTitle, shareDesc)
                    val showDonate = aboutSectionMatch || matchesSetting(donateTitle, donateDesc, listOf("support", "sponsor"))

                    if (showAbout || showDocumentation || showLogs || showShare || showDonate) {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            title = { Text(aboutSectionTitle) },
                        ) {
                            if (showAbout) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingAbout) },
                                    leadingContent = { Icon(HugeIcons.Clapping01, null) },
                                    trailingContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    context.joinQQGroup("Qsm0whzbPsm1UyNpR683ulLyMZ2Pqrw0")
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = TencentQQIcon,
                                                    contentDescription = "QQ",
                                                    tint = MaterialTheme.colorScheme.secondary,
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
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                )
                                            }
                                        }
                                    },
                                    supportingContent = { Text(aboutDesc) },
                                    headlineContent = { Text(aboutTitle) },
                                )
                            }
                            if (showDocumentation) {
                                item(
                                    onClick = { context.openUrl(APP_README_URL) },
                                    leadingContent = { Icon(HugeIcons.Book01, null) },
                                    supportingContent = { Text(documentationDesc) },
                                    headlineContent = { Text(documentationTitle) },
                                )
                            }
                            if (showLogs) {
                                item(
                                    onClick = {
                                        navController.navigate(Screen.Log)
                                    },
                                    leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                                    supportingContent = { Text(logsDesc) },
                                    headlineContent = { Text(logsTitle) },
                                )
                            }
                            if (showShare) {
                                item(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_SEND)
                                            intent.type = "text/plain"
                                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                                            context.startActivity(Intent.createChooser(intent, share))
                                        } catch (e: ActivityNotFoundException) {
                                            Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    leadingContent = { Icon(HugeIcons.Share04, null) },
                                    supportingContent = { Text(shareDesc) },
                                    headlineContent = { Text(shareTitle) },
                                )
                            }
                            if (showDonate) {
                                item(
                                    onClick = { navController.navigate(Screen.SettingDonate) },
                                    leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                                    supportingContent = { Text(donateDesc) },
                                    headlineContent = { Text(donateTitle) },
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
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
                    Icon(HugeIcons.Alert01, null)
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
