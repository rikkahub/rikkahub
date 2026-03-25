package me.rerere.rikkahub.ui.pages.persona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.UserPersonaProfile
import me.rerere.rikkahub.data.model.effectiveUserAvatar
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.selectedUserPersonaProfile
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun UserPersonaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selectedProfile = settings.selectedUserPersonaProfile()

    var editorDraft by remember { mutableStateOf<UserPersonaDraft?>(null) }
    var pendingDeleteProfileId by remember { mutableStateOf<Uuid?>(null) }

    fun openCreatePersona() {
        val shouldPrefillFromCurrent = settings.userPersonaProfiles.isEmpty()
        editorDraft = UserPersonaDraft(
            id = null,
            originalAvatar = if (shouldPrefillFromCurrent) {
                settings.effectiveUserAvatar()
            } else {
                Avatar.Dummy
            },
            name = if (shouldPrefillFromCurrent) {
                settings.effectiveUserName().ifBlank { "默认 Persona" }
            } else {
                nextPersonaProfileName(settings.userPersonaProfiles)
            },
            avatar = if (shouldPrefillFromCurrent) {
                settings.effectiveUserAvatar()
            } else {
                Avatar.Dummy
            },
            content = "",
            selectOnSave = true,
        )
    }

    fun openEditPersona(profile: UserPersonaProfile) {
        editorDraft = UserPersonaDraft(
            id = profile.id,
            originalAvatar = profile.avatar,
            name = profile.name,
            avatar = profile.avatar,
            content = profile.content,
            selectOnSave = false,
        )
    }

    fun discardDraft(draft: UserPersonaDraft?) {
        if (draft == null) return
        disposeAvatarIfNeeded(
            filesManager = filesManager,
            previous = draft.avatar,
            original = draft.originalAvatar,
        )
    }

    fun dismissEditor() {
        discardDraft(editorDraft)
        editorDraft = null
    }

    fun saveDraft(draft: UserPersonaDraft) {
        val profile = UserPersonaProfile(
            id = draft.id ?: Uuid.random(),
            name = draft.name.trim(),
            avatar = draft.avatar,
            content = draft.content,
        )
        val existing = settings.userPersonaProfiles.firstOrNull { it.id == profile.id }
        val profiles = if (existing == null) {
            settings.userPersonaProfiles + profile
        } else {
            settings.userPersonaProfiles.map { current ->
                if (current.id == profile.id) profile else current
            }
        }
        val nextSelectedId = when {
            draft.selectOnSave -> profile.id
            settings.selectedUserPersonaProfileId == profile.id -> profile.id
            settings.selectedUserPersonaProfileId == null -> profile.id
            else -> settings.selectedUserPersonaProfileId
        }
        vm.updateSettings(
            settings.copy(
                userPersonaProfiles = profiles,
                selectedUserPersonaProfileId = nextSelectedId,
            )
        )
        editorDraft = null
    }

    fun deleteProfile(profileId: Uuid) {
        val remainingProfiles = settings.userPersonaProfiles.filterNot { it.id == profileId }
        val nextSelectedId = when {
            remainingProfiles.isEmpty() -> null
            settings.selectedUserPersonaProfileId == profileId -> remainingProfiles.first().id
            else -> settings.selectedUserPersonaProfileId
        }
        vm.updateSettings(
            settings.copy(
                userPersonaProfiles = remainingProfiles,
                selectedUserPersonaProfileId = nextSelectedId,
            )
        )
        if (editorDraft?.id == profileId) {
            editorDraft = null
        }
        pendingDeleteProfileId = null
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Persona")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CustomColors.cardColorsOnSurfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "当前 Persona",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            UIAvatar(
                                name = settings.effectiveUserName().ifBlank { stringResource(R.string.user_default_name) },
                                value = settings.effectiveUserAvatar(),
                                modifier = Modifier.size(64.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = settings.effectiveUserName().ifBlank { stringResource(R.string.user_default_name) },
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = selectedProfile?.content?.trim().orEmpty().ifBlank {
                                        if (settings.userPersonaProfiles.isEmpty()) {
                                            "当前还没有 Persona。创建后会统一驱动用户头像、显示名称、`personaDescription` 和 `{{persona}}`。"
                                        } else {
                                            "当前 Persona 暂未填写描述。"
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Button(
                            onClick = { openCreatePersona() }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("新增 Persona")
                        }
                    }
                }
            }

            if (settings.userPersonaProfiles.isEmpty()) {
                item {
                    Card(
                        colors = CustomColors.cardColorsOnSurfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "还没有可切换的人设",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "从这里开始管理你的用户形象。每个 Persona 都有独立的头像、显示名称和 Persona Description。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(
                    items = settings.userPersonaProfiles,
                    key = { it.id.toString() },
                ) { profile ->
                    PersonaListCard(
                        profile = profile,
                        selected = selectedProfile?.id == profile.id,
                        onSelect = {
                            vm.updateSettings(
                                settings.copy(selectedUserPersonaProfileId = profile.id)
                            )
                        },
                        onEdit = { openEditPersona(profile) },
                    )
                }
            }
        }
    }

    editorDraft?.let { draft ->
        UserPersonaEditorSheet(
            draft = draft,
            onDraftChange = { nextDraft ->
                editorDraft = nextDraft
            },
            onAvatarChange = { nextAvatar ->
                val currentDraft = editorDraft ?: return@UserPersonaEditorSheet
                disposeAvatarIfNeeded(
                    filesManager = filesManager,
                    previous = currentDraft.avatar,
                    original = currentDraft.originalAvatar,
                )
                editorDraft = currentDraft.copy(avatar = nextAvatar)
            },
            onDismiss = { dismissEditor() },
            onSave = { saveDraft(it) },
            onDelete = { draftProfile ->
                pendingDeleteProfileId = draftProfile.id
            },
        )
    }

    pendingDeleteProfileId?.let { profileId ->
        val profile = settings.userPersonaProfiles.firstOrNull { it.id == profileId }
        if (profile != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteProfileId = null },
                title = {
                    Text("删除 Persona")
                },
                text = {
                    Text("确认删除 `${profile.name.ifBlank { "未命名 Persona" }}` 吗？")
                },
                confirmButton = {
                    TextButton(
                        onClick = { deleteProfile(profileId) }
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDeleteProfileId = null }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun PersonaListCard(
    profile: UserPersonaProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UIAvatar(
                name = profile.name.ifBlank { stringResource(R.string.user_default_name) },
                value = profile.avatar,
                modifier = Modifier.size(52.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = profile.name.ifBlank { "未命名 Persona" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            text = "当前使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = profile.content.trim().ifBlank { "未填写 Persona Description" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onEdit
            ) {
                Icon(
                    imageVector = HugeIcons.PencilEdit01,
                    contentDescription = "Edit Persona",
                )
            }
        }
    }
}

@Composable
private fun UserPersonaEditorSheet(
    draft: UserPersonaDraft,
    onDraftChange: (UserPersonaDraft) -> Unit,
    onAvatarChange: (Avatar) -> Unit,
    onDismiss: () -> Unit,
    onSave: (UserPersonaDraft) -> Unit,
    onDelete: (UserPersonaDraft) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (draft.id == null) "新建 Persona" else "编辑 Persona",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onDismiss
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(draft) }
                ) {
                    Text("保存")
                }
            }

            Card(
                colors = CustomColors.cardColorsOnSurfaceContainer
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        UIAvatar(
                            name = draft.name.ifBlank { stringResource(R.string.user_default_name) },
                            value = draft.avatar,
                            modifier = Modifier.size(84.dp),
                            onUpdate = onAvatarChange,
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "这张头像和显示名称会直接反映到聊天里的用户侧显示。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Persona Description 会参与 `personaDescription`、`{{persona}}` 和相关 lorebook 匹配。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value ->
                            onDraftChange(draft.copy(name = value))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("显示名称") },
                        singleLine = true,
                    )
                }
            }

            OutlinedTextField(
                value = draft.content,
                onValueChange = { value ->
                    onDraftChange(draft.copy(content = value))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Persona Description") },
                minLines = 12,
            )

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (draft.id != null) {
                    TextButton(
                        onClick = { onDelete(draft) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "删除 Persona",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun disposeAvatarIfNeeded(
    filesManager: FilesManager,
    previous: Avatar,
    original: Avatar,
) {
    if (previous !is Avatar.Image) return
    if (previous == original) return
    if (!previous.url.startsWith("file:")) return
    filesManager.deleteChatFiles(listOf(previous.url.toUri()))
}

private fun nextPersonaProfileName(
    profiles: List<UserPersonaProfile>,
): String {
    if (profiles.isEmpty()) return "默认 Persona"

    val existingNames = profiles.map { it.name.trim() }.toSet()
    var index = 2
    while (true) {
        val candidate = "Persona $index"
        if (candidate !in existingNames) {
            return candidate
        }
        index++
    }
}

private data class UserPersonaDraft(
    val id: Uuid?,
    val originalAvatar: Avatar,
    val name: String,
    val avatar: Avatar,
    val content: String,
    val selectOnSave: Boolean,
)
