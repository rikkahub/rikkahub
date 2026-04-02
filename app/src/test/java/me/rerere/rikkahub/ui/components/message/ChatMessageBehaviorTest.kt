package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.UserPersonaProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageBehaviorTest {
    @Test
    fun `primary actions should show for non-loading message with content`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello"))
        )

        assertTrue(message.shouldShowPrimaryActions(loading = false))
    }

    @Test
    fun `primary actions should hide while message is loading`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello"))
        )

        assertFalse(message.shouldShowPrimaryActions(loading = true))
    }

    @Test
    fun `primary actions should hide for empty message`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList()
        )

        assertFalse(message.shouldShowPrimaryActions(loading = false))
    }

    @Test
    fun `regex render cache key should change when selected persona changes`() {
        val profile = UserPersonaProfile(name = "Alice")
        val renamedProfile = profile.copy(name = "Bob")
        val initial = Settings(
            userPersonaProfiles = listOf(profile),
            selectedUserPersonaProfileId = profile.id,
        )
        val updated = initial.copy(
            userPersonaProfiles = listOf(renamedProfile),
        )

        assertNotEquals(userRegexRenderCacheKey(initial), userRegexRenderCacheKey(updated))
    }

    @Test
    fun `regex render cache key should change when fallback nickname changes`() {
        val initial = Settings(
            displaySetting = DisplaySetting(userNickname = "Alice"),
        )
        val updated = initial.copy(
            displaySetting = initial.displaySetting.copy(userNickname = "Bob"),
        )

        assertNotEquals(userRegexRenderCacheKey(initial), userRegexRenderCacheKey(updated))
    }

    @Test
    fun `header state should show user avatar and label when enabled`() {
        val settings = Settings(
            displaySetting = DisplaySetting(
                showUserAvatar = true,
            ),
        )
        val message = UIMessage(
            role = MessageRole.USER,
            parts = emptyList(),
        )

        val headerState = message.headerState(
            settings = settings,
            showIdentity = true,
            model = null,
            assistant = null,
        )

        assertTrue(headerState.showAvatar)
        assertTrue(headerState.showIdentityLabel)
        assertTrue(headerState.isVisible)
    }

    @Test
    fun `header state should hide assistant avatar when no visible identity source exists`() {
        val settings = Settings(
            displaySetting = DisplaySetting(
                showModelIcon = true,
                showModelName = true,
                showDateBelowName = false,
            ),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
        )

        val headerState = message.headerState(
            settings = settings,
            showIdentity = true,
            model = null,
            assistant = null,
        )

        assertFalse(headerState.showAvatar)
        assertFalse(headerState.showIdentityLabel)
        assertFalse(headerState.isVisible)
    }

    @Test
    fun `header state should keep assistant header visible with assistant avatar`() {
        val settings = Settings(
            displaySetting = DisplaySetting(
                showModelIcon = true,
                showModelName = true,
            ),
        )
        val assistant = Assistant(
            name = "Rikka",
            useAssistantAvatar = true,
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
        )

        val headerState = message.headerState(
            settings = settings,
            showIdentity = true,
            model = null,
            assistant = assistant,
        )

        assertTrue(headerState.showAvatar)
        assertTrue(headerState.showIdentityLabel)
        assertTrue(headerState.isVisible)
    }
}
