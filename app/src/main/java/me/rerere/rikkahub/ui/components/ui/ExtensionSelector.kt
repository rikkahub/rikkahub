package me.rerere.rikkahub.ui.components.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject

internal enum class ExtensionTab(val icon: ImageVector, @param:StringRes val label: Int) {
    QUICK_MESSAGES(HugeIcons.Zap, R.string.extension_selector_tab_quick_messages),
    MODE_INJECTIONS(HugeIcons.MagicWand01, R.string.extension_selector_tab_mode_injections),
    LOREBOOKS(HugeIcons.Book01, R.string.extension_selector_tab_lorebooks),
    SKILLS(HugeIcons.Puzzle, R.string.extension_selector_tab_skills),
}


@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val pagerState = rememberPagerState { ExtensionTab.entries.size }

    Column(
        modifier = modifier
    ) {
        ExtensionTabs(
            pagerState = pagerState,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            val tab = ExtensionTab.entries[page]
            when (tab) {
                ExtensionTab.QUICK_MESSAGES -> {
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
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.MODE_INJECTIONS -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsContent(
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
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.LOREBOOKS -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
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
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.SKILLS -> {
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
                            icon = tab.icon,
                        )
                    }
                }
            }
        }
    }
}

// 分类切换：可横向滚动的切换按钮，选中项带图标
@Composable
internal fun ExtensionTabs(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val scope = rememberCoroutineScope()
    val tabs = ExtensionTab.entries
    // 用 targetPage 而不是 currentPage，跨多页跳转时中间的按钮不会依次闪过
    val selectedIndex = pagerState.targetPage
    val requesters = remember { tabs.map { BringIntoViewRequester() } }

    // 滑动翻页后，把选中的按钮滚进可视范围
    LaunchedEffect(selectedIndex) {
        requesters[selectedIndex].bringIntoView()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.fastForEachIndexed { index, tab ->
            val selected = index == selectedIndex
            ToggleButton(
                checked = selected,
                onCheckedChange = {
                    if (!selected) scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier
                    .bringIntoViewRequester(requesters[index])
                    .semantics { role = Role.Tab },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                AnimatedVisibility(
                    visible = selected,
                    enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
                    exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                ) {
                    Row {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                    }
                }
                Text(
                    text = stringResource(tab.label),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
