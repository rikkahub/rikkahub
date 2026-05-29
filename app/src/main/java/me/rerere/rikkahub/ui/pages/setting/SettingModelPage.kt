package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.Message01, null) },
                title = { Text(stringResource(R.string.setting_model_page_chat_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_chat_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.chatModelId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
                        providers = settings.providers,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.Notebook01, null) },
                title = { Text(stringResource(R.string.setting_model_page_title_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_title_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.titleModelId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                        providers = settings.providers,
                        allowClear = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.MessageMultiple01, null) },
                title = { Text(stringResource(R.string.setting_model_page_suggestion_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_suggestion_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.suggestionModelId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) },
                        providers = settings.providers,
                        allowClear = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.Earth, null) },
                title = { Text(stringResource(R.string.setting_model_page_translate_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_translate_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.translateModeId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
                        providers = settings.providers,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.View, null) },
                title = { Text(stringResource(R.string.setting_model_page_ocr_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_ocr_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.ocrModelId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
                        providers = settings.providers,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
        item {
            ModelFeatureCard(
                icon = { Icon(HugeIcons.FileZip, null) },
                title = { Text(stringResource(R.string.setting_model_page_compress_model), maxLines = 1) },
                description = { Text(stringResource(R.string.setting_model_page_compress_model_desc)) },
                actions = {
                    ModelSelector(
                        modelId = settings.compressModelId,
                        type = ModelType.CHAT,
                        onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
                        providers = settings.providers,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }
    }
}

@Composable
internal fun ModelFeatureCard(
    modifier: Modifier = Modifier,
    description: @Composable () -> Unit = {},
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                        title()
                    }
                    ProvideTextStyle(
                        MaterialTheme.typography.bodySmall.copy(
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    ) {
                        description()
                    }
                }
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
