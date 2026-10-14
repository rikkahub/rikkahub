package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.asr.ASRProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderEditSheet
import me.rerere.rikkahub.ui.pages.setting.components.ProviderTypeSheet
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class SpeechTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    TTS(HugeIcons.VolumeHigh, R.string.speech_tab_tts),
    ASR(HugeIcons.Mic01, R.string.speech_tab_asr);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val pagerState = rememberPagerState { SpeechTab.entries.size }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.speech_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    when (SpeechTab.entries[pagerState.targetPage]) {
                        SpeechTab.TTS -> AddTTSProviderButton {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    ttsProviders = listOf(it) + latest.ttsProviders
                                )
                            }
                        }

                        SpeechTab.ASR -> AddASRProviderButton {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    asrProviders = listOf(it) + latest.asrProviders,
                                    selectedASRProviderId = latest.selectedASRProviderId ?: it.id
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = SpeechTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (SpeechTab.entries[page]) {
                    SpeechTab.TTS -> TTSProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingTTSProvider = it },
                    )

                    SpeechTab.ASR -> ASRProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingASRProvider = it },
                    )
                }
            }
        }
    }

    editingTTSProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_tts_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(ttsProviders = latest.ttsProviders.map { if (it.id == provider.id) edited else it })
                }
            },
            onDismiss = { editingTTSProvider = null },
        ) { value, onValueChange, modifier ->
            TTSProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }

    editingASRProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(asrProviders = latest.asrProviders.map { if (it.id == provider.id) edited else it })
                }
            },
            onDismiss = { editingASRProvider = null },
        ) { value, onValueChange, modifier ->
            ASRProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }
}

@Composable
private fun TTSProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (TTSProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.ttsProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings { it.copy(ttsProviders = newProviders) }
    }
    var deleteTarget by remember { mutableStateOf<TTSProviderSetting?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(settings.ttsProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                val isSelected = settings.selectedTTSProviderId == provider.id
                SpeechProviderItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressReorder(isDragging)),
                    name = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                    typeName = provider.typeName,
                    isSelected = isSelected,
                    deletable = provider.id != DEFAULT_SYSTEM_TTS_ID,
                    onSelect = {
                        onUpdateSettings { it.copy(selectedTTSProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        deleteTarget = provider
                    },
                    action = if (isSelected) {
                        { TTSTestButton() }
                    } else null,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                val newProviders = settings.ttsProviders.filter { it.id != target.id }
                val newSelectedId =
                    if (settings.selectedTTSProviderId == target.id) DEFAULT_SYSTEM_TTS_ID else settings.selectedTTSProviderId
                onUpdateSettings {
                    it.copy(
                        ttsProviders = newProviders,
                        selectedTTSProviderId = newSelectedId
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

@Composable
private fun ASRProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.asrProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings { it.copy(asrProviders = newProviders) }
    }
    var deleteTarget by remember { mutableStateOf<ASRProviderSetting?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(settings.asrProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                SpeechProviderItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressReorder(isDragging)),
                    name = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                    typeName = provider.typeName,
                    isSelected = settings.selectedASRProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings { it.copy(selectedASRProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        deleteTarget = provider
                    }
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                val newProviders = settings.asrProviders.filter { it.id != target.id }
                val newSelectedId =
                    if (settings.selectedASRProviderId == target.id) {
                        newProviders.firstOrNull()?.id
                    } else {
                        settings.selectedASRProviderId
                    }
                onUpdateSettings {
                    it.copy(
                        asrProviders = newProviders,
                        selectedASRProviderId = newSelectedId
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

// 类型在配置面板里选，新建时默认是系统 TTS
@Composable
private fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var newProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }

    IconButton(
        onClick = { newProvider = TTSProviderSetting.SystemTTS() }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    newProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_tts_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null },
        ) { value, onValueChange, modifier ->
            TTSProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }
}

// 新建时先选类型，再进配置面板
@Composable
private fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showTypeSheet by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val factories = remember {
        listOf<() -> ASRProviderSetting>(
            { ASRProviderSetting.OpenAIRealtime() },
            { ASRProviderSetting.DashScope() },
            { ASRProviderSetting.Volcengine() },
            { ASRProviderSetting.MiMo() },
            { ASRProviderSetting.Step() },
        )
    }

    IconButton(
        onClick = { showTypeSheet = true }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
    }

    if (showTypeSheet) {
        ProviderTypeSheet(
            title = stringResource(R.string.setting_asr_page_add_provider),
            types = factories,
            typeName = { create -> remember(create) { create() }.typeName },
            onSelect = { create ->
                showTypeSheet = false
                newProvider = create()
            },
            onDismiss = { showTypeSheet = false },
        )
    }

    newProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null },
        ) { value, onValueChange, modifier ->
            ASRProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }
}

private val TTSProviderSetting.typeName: String
    @Composable get() = when (this) {
        is TTSProviderSetting.OpenAI -> stringResource(R.string.setting_tts_page_provider_openai)
        is TTSProviderSetting.Gemini -> stringResource(R.string.setting_tts_page_provider_gemini)
        is TTSProviderSetting.MiniMax -> "MiniMax"
        is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
        is TTSProviderSetting.Qwen -> "Qwen"
        is TTSProviderSetting.Groq -> "Groq"
        is TTSProviderSetting.XAI -> "xAI"
        is TTSProviderSetting.MiMo -> "MiMo"
        is TTSProviderSetting.Step -> "Step"
        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
        is TTSProviderSetting.FishAudio -> "Fish Audio"
        is TTSProviderSetting.Volcengine -> "火山引擎"
    }

// TTS 和 ASR 列表共用的服务商卡片，点卡片编辑，点单选圈设为当前使用
@Composable
private fun SpeechProviderItem(
    name: String,
    typeName: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deletable: Boolean = true,
    // 跟在标签后面的操作，比如试听
    action: (@Composable () -> Unit)? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Card(
        onClick = onEdit,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(40.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = typeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
                if (isSelected) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.setting_tts_page_selected))
                        }
                        action?.invoke()
                    }
                }
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        enabled = deletable,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

// 用当前选中的 TTS 念一句测试文本，播放中再点一次停止
@Composable
private fun TTSTestButton() {
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsState()
    val isAvailable by tts.isAvailable.collectAsState()
    if (!isAvailable) return

    val testText = stringResource(R.string.setting_tts_page_test_text)
    val height = ButtonDefaults.ExtraSmallContainerHeight
    Button(
        onClick = {
            if (!isSpeaking) {
                tts.speak(testText)
            } else {
                tts.stop()
            }
        },
        // 按下时圆角收紧
        shapes = ButtonDefaults.shapesFor(height),
        modifier = Modifier.heightIn(min = height),
        colors = if (isSpeaking) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else ButtonDefaults.buttonColors(),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
    ) {
        Icon(
            imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(
            text = if (isSpeaking) stringResource(R.string.stop) else stringResource(R.string.test_tts),
            style = ButtonDefaults.textStyleFor(height),
        )
    }
}
