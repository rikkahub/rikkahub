package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection


@Composable
fun InjectionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    onNavigateToPrompts: () -> Unit = {},
) {
    // Empty state
    if (settings.modeInjections.isEmpty() && settings.lorebooks.isEmpty()) {
        InjectionEmptyState(
            modifier = modifier,
            onNavigateToPrompts = onNavigateToPrompts,
        )
        return
    }

    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                modifier = Modifier.weight(1f),
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text(stringResource(R.string.injection_selector_mode_injections))
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text(stringResource(R.string.injection_selector_lorebooks))
                    }
                )
            }
            IconButton(onClick = onNavigateToPrompts) {
                Icon(Lucide.ExternalLink, contentDescription = null)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsSection(
                            modeInjections = settings.modeInjections,
                            selectedIds = assistant.modeInjectionIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.modeInjectionIds + id
                                } else {
                                    assistant.modeInjectionIds - id
                                }
                                onUpdate(assistant.copy(modeInjectionIds = newIds))
                            },

                            )
                    } else {
                        ModeInjectionsEmptyState()
                    }
                }

                1 -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksSection(
                            lorebooks = settings.lorebooks,
                            selectedIds = assistant.lorebookIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.lorebookIds + id
                                } else {
                                    assistant.lorebookIds - id
                                }
                                onUpdate(assistant.copy(lorebookIds = newIds))
                            },
                        )
                    } else {
                        LorebooksEmptyState()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeInjectionsSection(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.injection_selector_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked ->
                            onToggle(injection.id, checked)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                )
            )
        }
    }
}

@Composable
private fun LorebooksSection(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(lorebooks) { lorebook ->
            ListItem(
                headlineContent = {
                    Text(lorebook.name.ifBlank { stringResource(R.string.injection_selector_unnamed_lorebook) })
                },
                supportingContent = if (lorebook.description.isNotBlank()) {
                    {
                        Text(
                            text = lorebook.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(lorebook.id),
                        onCheckedChange = { checked ->
                            onToggle(lorebook.id, checked)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                )
            )
        }
    }
}

@Composable
private fun InjectionEmptyState(
    modifier: Modifier = Modifier,
    onNavigateToPrompts: () -> Unit = {},
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.injection_selector_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = stringResource(R.string.injection_selector_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            TextButton(onClick = onNavigateToPrompts) {
                Icon(Lucide.ExternalLink, contentDescription = null)
                Text(stringResource(R.string.injection_selector_go_to_prompts))
            }
        }
    }

    content()
}

@Composable
private fun ModeInjectionsEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.injection_selector_mode_injections_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LorebooksEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.injection_selector_lorebooks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
