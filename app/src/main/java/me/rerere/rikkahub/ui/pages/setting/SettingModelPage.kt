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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.GraduationCap
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquareMore
import com.composables.icons.lucide.NotebookTabs
import com.composables.icons.lucide.Settings2
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_model_page_title))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DefaultChatModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultTitleModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultSuggestionModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultTranslationModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultOcrModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultCompressModelSetting(settings = settings, vm = vm)
            }
        }
    }
}

@Composable
private fun DefaultTranslationModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_translate_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_translate_model_desc))
        },
        icon = {
            Icon(Lucide.Earth, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.translateModeId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                translateModeId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Lucide.Settings2, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_translate_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.translatePrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    translatePrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    translatePrompt = DEFAULT_TRANSLATION_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSuggestionModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                text = stringResource(R.string.setting_model_page_suggestion_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_suggestion_model_desc))
        },
        icon = {
            Icon(Lucide.MessageSquareMore, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.suggestionModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                suggestionModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    allowClear = true,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Lucide.Settings2, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.suggestionPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    suggestionPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    suggestionPrompt = DEFAULT_SUGGESTION_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultTitleModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(stringResource(R.string.setting_model_page_title_model), maxLines = 1)
        },
        description = {
            Text(stringResource(R.string.setting_model_page_title_model_desc))
        },
        icon = {
            Icon(Lucide.NotebookTabs, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.titleModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                titleModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    allowClear = true,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Lucide.Settings2, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.titlePrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    titlePrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    titlePrompt = DEFAULT_TITLE_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultChatModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    ModelFeatureCard(
        icon = {
            Icon(Lucide.MessageCircle, null)
        },
        title = {
            Text(stringResource(R.string.setting_model_page_chat_model), maxLines = 1)
        },
        description = {
            Text(stringResource(R.string.setting_model_page_chat_model_desc))
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.chatModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                chatModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
        }
    )
}

@Composable
private fun DefaultOcrModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_ocr_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_ocr_model_desc))
        },
        icon = {
            Icon(Lucide.Eye, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.ocrModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                ocrModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Lucide.Settings2, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_ocr_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.ocrPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    ocrPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    ocrPrompt = DEFAULT_OCR_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultCompressModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_compress_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_compress_model_desc))
        },
        icon = {
            Icon(Lucide.FileArchive, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.compressModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                compressModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Lucide.Settings2, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_compress_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.compressPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    compressPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    compressPrompt = DEFAULT_COMPRESS_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelFeatureCard(
    modifier: Modifier = Modifier,
    description: @Composable () -> Unit = {},
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
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
                    modifier = Modifier
                        .size(40.dp),
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
