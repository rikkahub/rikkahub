package me.rerere.rikkahub.ui.components.ai.chatinput

import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.slash.SlashCommand
import me.rerere.rikkahub.data.ai.slash.filterSlashCommands
import me.rerere.rikkahub.data.ai.slash.reservedSlashCommands
import me.rerere.rikkahub.data.ai.tools.SKILL_AUTHORING_SUPPORTED
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun TextInputRow(
    state: ChatInputState,
    assistant: Assistant,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    // Slash-command skill picker (user request): typing a leading "/" surfaces a skills popup above the
    // composer. Skills load once off the main thread; the query is the text after "/" up to the first
    // space, so "/tts" filters while typing past the command (a space) dismisses the popup.
    val skillManager: SkillManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val navController = LocalNavController.current
    val allSkills by produceState(initialValue = emptyList<SkillMetadata>()) {
        value = withContext(Dispatchers.IO) { skillManager.listSkills() }
    }
    var slashQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.textContent.text.toString() }.collect { text ->
            slashQuery = if (text.startsWith("/") && !text.contains(' ') && !text.contains('\n')) {
                text.substring(1)
            } else {
                null
            }
        }
    }
    // Built-in reserved commands (#364: /goal, /loop) lead, then the matching skills. One unified
    // registry (filterSlashCommands) merges the flavor's reserved set with the live skills.
    val slashItems = remember(slashQuery, allSkills) {
        filterSlashCommands(slashQuery, reservedSlashCommands(SKILL_AUTHORING_SUPPORTED), allSkills)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                scope.launch {
                                    state.addImages(filesManager.createChatFilesByContents(listOf(uri)))
                                }
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                scope.launch {
                                    state.addFiles(listOf(filesManager.createChatTextFile(text)))
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }
        if (slashQuery != null) {
            SlashSkillPopup(
                items = slashItems,
                onSelect = { item ->
                    when (item) {
                        // A reserved native command (#364: /goal, /loop) has NO skill behind it: just drop
                        // "/<name> " into the input. The send path (ChatVM resolveSlashCommand) runs it as a
                        // reserved invocation BEFORE any skill rewrite, so arming a use_skill here would be wrong.
                        is SlashCommand.Reserved -> state.setMessageText("/${item.name} ")
                        // A skill: arm it on the active assistant NOW (so its use_skill tool is exposed by
                        // the time the message is sent) and drop "/<name> " in for optional params. The send
                        // path then rewrites "/<name> ..." into a use_skill directive (longest-prefix match,
                        // so it also handles names with spaces).
                        is SlashCommand.Skill -> {
                            scope.launch {
                                settingsStore.update { s ->
                                    s.copy(
                                        assistants = s.assistants.map { a ->
                                            if (a.id == assistant.id) {
                                                a.copy(enabledSkills = a.enabledSkills + item.name)
                                            } else {
                                                a
                                            }
                                        }
                                    )
                                }
                            }
                            state.setMessageText("/${item.name} ")
                        }
                    }
                },
                onManage = { navController.navigate(Screen.Skills) },
            )
        }
        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}
