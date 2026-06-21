package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject


internal const val QUICK_MESSAGES_PAGE_INDEX = 0
internal const val MODE_INJECTIONS_PAGE_INDEX = 1
internal const val LOREBOOKS_PAGE_INDEX = 2
internal const val SKILLS_PAGE_INDEX = 3
private const val EXTENSION_PAGE_COUNT = 4

/** Suspends until this flow first emits [target], then returns. Cancels the upstream after the
 *  first match, so any work guarded behind it runs at most once per collection. */
internal suspend fun Flow<Int>.awaitPage(target: Int) {
    filter { it == target }.first()
}


@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeInjectionIds = if (useConversationInjections) {
        conversation.modeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val selectedLorebookIds = if (useConversationInjections) {
        conversation.lorebookIds
    } else {
        assistant.lorebookIds
    }

    val pagerState = rememberPagerState(initialPage = QUICK_MESSAGES_PAGE_INDEX) { EXTENSION_PAGE_COUNT }
    val scope = rememberCoroutineScope()

    // Defer the skills disk IO until the user actually settles on the Skills tab. Loading is
    // single-shot per sheet instance: awaitPage cancels the snapshotFlow after the first match.
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.settledPage }.awaitPage(SKILLS_PAGE_INDEX)
        withContext(Dispatchers.IO) {
            skills = skillManager.listSkills()
        }
    }

    Column(
        modifier = modifier
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 4.dp,
        ) {
            Tab(
                selected = pagerState.currentPage == QUICK_MESSAGES_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(QUICK_MESSAGES_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_quick_messages)) }
            )
            Tab(
                selected = pagerState.currentPage == MODE_INJECTIONS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(MODE_INJECTIONS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_mode_injections)) }
            )
            Tab(
                selected = pagerState.currentPage == LOREBOOKS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(LOREBOOKS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_lorebooks)) }
            )
            Tab(
                selected = pagerState.currentPage == SKILLS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(SKILLS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_skills)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            key = { page -> page },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                QUICK_MESSAGES_PAGE_INDEX -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.quickMessageIds + id
                                } else {
                                    assistant.quickMessageIds - id
                                }
                                onUpdate(assistant.copy(quickMessageIds = newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                MODE_INJECTIONS_PAGE_INDEX -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsContent(
                            modeInjections = settings.modeInjections,
                            selectedIds = selectedModeInjectionIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedModeInjectionIds + id
                                } else {
                                    selectedModeInjectionIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(modeInjectionIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(modeInjectionIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                LOREBOOKS_PAGE_INDEX -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedLorebookIds + id
                                } else {
                                    selectedLorebookIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(lorebookIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                SKILLS_PAGE_INDEX -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                val newSkills = if (checked) {
                                    assistant.enabledSkills + name
                                } else {
                                    assistant.enabledSkills - name
                                }
                                onUpdate(assistant.copy(enabledSkills = newSkills))
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                        )
                    }
                }
            }
        }
    }
}
