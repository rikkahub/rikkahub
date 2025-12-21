package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Syringe
import com.composables.icons.lucide.Wrench
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.heroAnimation
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = assistant.name.ifBlank {
                            stringResource(R.string.assistant_page_default_assistant)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    BackButton()
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 头像和名字区域
            AssistantHeader(
                assistant = assistant,
                modifier = Modifier.padding(24.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 设置卡片列表
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingCard(
                    icon = Lucide.Settings,
                    title = stringResource(R.string.assistant_page_tab_basic),
                    description = stringResource(R.string.assistant_detail_basic_desc),
                    onClick = { navController.navigate(Screen.AssistantBasic(id)) }
                )

                SettingCard(
                    icon = Lucide.MessageSquare,
                    title = stringResource(R.string.assistant_page_tab_prompt),
                    description = stringResource(R.string.assistant_detail_prompt_desc),
                    onClick = { navController.navigate(Screen.AssistantPrompt(id)) }
                )

                SettingCard(
                    icon = Lucide.Syringe,
                    title = stringResource(R.string.assistant_page_tab_injections),
                    description = stringResource(R.string.assistant_detail_injections_desc),
                    onClick = { navController.navigate(Screen.AssistantInjections(id)) }
                )

                SettingCard(
                    icon = Lucide.Brain,
                    title = stringResource(R.string.assistant_page_tab_memory),
                    description = stringResource(R.string.assistant_detail_memory_desc),
                    onClick = { navController.navigate(Screen.AssistantMemory(id)) }
                )

                SettingCard(
                    icon = Lucide.Code,
                    title = stringResource(R.string.assistant_page_tab_request),
                    description = stringResource(R.string.assistant_detail_request_desc),
                    onClick = { navController.navigate(Screen.AssistantRequest(id)) }
                )

                SettingCard(
                    icon = Lucide.Wrench,
                    title = stringResource(R.string.assistant_page_tab_mcp),
                    description = stringResource(R.string.assistant_detail_mcp_desc),
                    onClick = { navController.navigate(Screen.AssistantMcp(id)) }
                )

                SettingCard(
                    icon = Lucide.BookOpen,
                    title = stringResource(R.string.assistant_page_tab_local_tools),
                    description = stringResource(R.string.assistant_detail_local_tools_desc),
                    onClick = { navController.navigate(Screen.AssistantLocalTool(id)) }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
            onUpdate = null,
            modifier = Modifier
                .size(100.dp)
                .heroAnimation("assistant_${assistant.id}")
        )

        Text(
            text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (assistant.systemPrompt.isNotBlank()) {
            Text(
                text = assistant.systemPrompt.take(100) + if (assistant.systemPrompt.length > 100) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
