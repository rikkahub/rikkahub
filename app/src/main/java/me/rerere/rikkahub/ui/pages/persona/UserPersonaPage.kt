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
    val defaultPersonaName = stringResource(R.string.persona_page_default_name)
    val personaNamePattern = stringResource(R.string.persona_page_name_pattern)

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
                settings.effectiveUserName().ifBlank { defaultPersonaName }
            } else {
                nextPersonaProfileName(settings.userPersonaProfiles, defaultPersonaName, personaNamePattern)
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
        val shouldClearDraft = discardDeletedProfileDraftIfNeeded(
            profileId = profileId,
            editorDraftId = editorDraft?.id,
        ) {
            discardDraft(editorDraft)
        }
        if (shouldClearDraft) {
            editorDraft = null
        }
        pendingDeleteProfileId = null
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.user_persona_page_title))
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
                            text = stringResource(R.string.user_persona_page_current_persona),
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
                                            stringResource(R.string.user_persona_page_current_empty_description)
                                        } else {
                                            stringResource(R.string.user_persona_page_current_missing_description)
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
                            Text(stringResource(R.string.persona_page_add))
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
                                text = stringResource(R.string.user_persona_page_empty_profiles_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.user_persona_page_empty_profiles_desc),
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
                    Text(stringResource(R.string.user_persona_page_delete_title))
                },
                text = {
                    Text(
                        stringResource(
                            R.string.user_persona_page_delete_message,
                            profile.name.ifBlank { stringResource(R.string.user_persona_page_unnamed) }
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { deleteProfile(profileId) }
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDeleteProfileId = null }
                    ) {
                        Text(stringResource(R.string.cancel))
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
                        text = profile.name.ifBlank { stringResource(R.string.user_persona_page_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            text = stringResource(R.string.user_persona_page_active_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = profile.content.trim().ifBlank { stringResource(R.string.persona_page_description_empty) },
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
                    contentDescription = stringResource(R.string.persona_page_edit_content_description),
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
                    text = if (draft.id == null) {
                        stringResource(R.string.user_persona_page_create_title)
                    } else {
                        stringResource(R.string.persona_page_edit_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onSave(draft) }
                ) {
                    Text(stringResource(R.string.save))
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
                                text = stringResource(R.string.persona_page_editor_identity_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.persona_page_editor_description_desc),
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
                        label = { Text(stringResource(R.string.persona_page_display_name)) },
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
                label = { Text(stringResource(R.string.persona_page_description)) },
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
                            text = stringResource(R.string.user_persona_page_delete_title),
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

internal fun discardDeletedProfileDraftIfNeeded(
    profileId: Uuid,
    editorDraftId: Uuid?,
    discardDraft: () -> Unit,
): Boolean {
    if (editorDraftId != profileId) return false
    discardDraft()
    return true
}

private fun nextPersonaProfileName(
    profiles: List<UserPersonaProfile>,
    defaultName: String,
    namePattern: String,
): String {
    if (profiles.isEmpty()) return defaultName

    val existingNames = profiles.map { it.name.trim() }.toSet()
    var index = 2
    while (true) {
        val candidate = namePattern.format(index)
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
