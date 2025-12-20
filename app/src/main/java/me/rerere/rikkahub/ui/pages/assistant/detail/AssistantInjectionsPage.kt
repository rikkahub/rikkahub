package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantInjectionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_injections))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { innerPadding ->
        AssistantInjectionsContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            settings = settings,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
internal fun AssistantInjectionsContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Injections Section
        if (settings.modeInjections.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Mode Injections",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    HorizontalDivider()
                    settings.modeInjections.forEach { injection ->
                        FormItem(
                            modifier = Modifier.padding(8.dp),
                            label = {
                                Text(injection.name.ifBlank { "Unnamed Injection" })
                            },
                            tail = {
                                Switch(
                                    checked = assistant.modeInjectionIds.contains(injection.id),
                                    onCheckedChange = { checked ->
                                        val newIds = if (checked) {
                                            assistant.modeInjectionIds + injection.id
                                        } else {
                                            assistant.modeInjectionIds - injection.id
                                        }
                                        onUpdate(assistant.copy(modeInjectionIds = newIds))
                                    }
                                )
                            }
                        )
                        if (injection != settings.modeInjections.last()) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // World Books Section
        if (settings.worldBooks.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = "World Books",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    HorizontalDivider()
                    settings.worldBooks.forEach { worldBook ->
                        FormItem(
                            modifier = Modifier.padding(8.dp),
                            label = {
                                Column {
                                    Text(worldBook.name.ifBlank { "Unnamed World Book" })
                                    if (worldBook.description.isNotBlank()) {
                                        Text(
                                            text = worldBook.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            },
                            tail = {
                                Switch(
                                    checked = assistant.worldBookIds.contains(worldBook.id),
                                    onCheckedChange = { checked ->
                                        val newIds = if (checked) {
                                            assistant.worldBookIds + worldBook.id
                                        } else {
                                            assistant.worldBookIds - worldBook.id
                                        }
                                        onUpdate(assistant.copy(worldBookIds = newIds))
                                    }
                                )
                            }
                        )
                        if (worldBook != settings.worldBooks.last()) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // Empty state
        if (settings.modeInjections.isEmpty() && settings.worldBooks.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No Prompt Injections Available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Create mode injections or world books in Settings > Prompt Injections",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
