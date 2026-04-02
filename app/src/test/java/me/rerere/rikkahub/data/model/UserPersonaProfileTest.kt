package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class UserPersonaProfileTest {
    @Test
    fun `selected profile should fall back to first profile when selection is missing`() {
        val first = UserPersonaProfile(id = Uuid.random(), name = "Default", content = "First persona")
        val second = UserPersonaProfile(id = Uuid.random(), name = "Alt", content = "Second persona")
        val settings = Settings(
            userPersonaProfiles = listOf(first, second),
            selectedUserPersonaProfileId = Uuid.random(),
        )

        assertEquals(first, settings.selectedUserPersonaProfile())
    }

    @Test
    fun `effective persona should prefer selected global profile over assistant legacy persona`() {
        val selected = UserPersonaProfile(
            id = Uuid.random(),
            name = "Archivist",
            content = "I speak like an archivist and keep meticulous notes.",
        )
        val settings = Settings(
            userPersonaProfiles = listOf(selected),
            selectedUserPersonaProfileId = selected.id,
        )

        assertEquals(
            "I speak like an archivist and keep meticulous notes.",
            settings.effectiveUserPersona(
                assistant = Assistant(userPersona = "Legacy assistant persona")
            )
        )
    }

    @Test
    fun `effective persona should allow blank selected profile without falling back`() {
        val selected = UserPersonaProfile(
            id = Uuid.random(),
            name = "Blank",
            content = "   ",
        )
        val settings = Settings(
            userPersonaProfiles = listOf(selected),
            selectedUserPersonaProfileId = selected.id,
        )

        assertEquals(
            "",
            settings.effectiveUserPersona(
                assistant = Assistant(userPersona = "Legacy assistant persona")
            )
        )
    }

    @Test
    fun `effective persona should fall back to assistant legacy persona when no global profile exists`() {
        val settings = Settings()

        assertEquals(
            "Legacy assistant persona",
            settings.effectiveUserPersona(
                assistant = Assistant(userPersona = "Legacy assistant persona")
            )
        )
    }

    @Test
    fun `effective user name should prefer selected profile name over legacy display nickname`() {
        val selected = UserPersonaProfile(
            id = Uuid.random(),
            name = "Seraphina",
        )
        val settings = Settings(
            displaySetting = Settings().displaySetting.copy(userNickname = "Legacy Name"),
            userPersonaProfiles = listOf(selected),
            selectedUserPersonaProfileId = selected.id,
        )

        assertEquals("Seraphina", settings.effectiveUserName())
    }

    @Test
    fun `effective user avatar should prefer selected profile avatar over legacy display avatar`() {
        val selected = UserPersonaProfile(
            id = Uuid.random(),
            name = "Seraphina",
            avatar = Avatar.Emoji("🙂"),
        )
        val settings = Settings(
            displaySetting = Settings().displaySetting.copy(userAvatar = Avatar.Emoji("😎")),
            userPersonaProfiles = listOf(selected),
            selectedUserPersonaProfileId = selected.id,
        )

        assertEquals(Avatar.Emoji("🙂"), settings.effectiveUserAvatar())
    }

    @Test
    fun `effective user name should allow blank selected profile name without falling back`() {
        val selected = UserPersonaProfile(
            id = Uuid.random(),
            name = "   ",
        )
        val settings = Settings(
            displaySetting = Settings().displaySetting.copy(userNickname = "Legacy Name"),
            userPersonaProfiles = listOf(selected),
            selectedUserPersonaProfileId = selected.id,
        )

        assertEquals("", settings.effectiveUserName())
    }
}
