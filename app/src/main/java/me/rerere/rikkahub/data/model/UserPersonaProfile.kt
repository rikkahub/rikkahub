package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

@Serializable
data class UserPersonaProfile(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val content: String = "",
)

fun Settings.selectedUserPersonaProfile(): UserPersonaProfile? {
    return userPersonaProfiles.firstOrNull { it.id == selectedUserPersonaProfileId }
        ?: userPersonaProfiles.firstOrNull()
}

fun Settings.effectiveUserPersona(assistant: Assistant? = null): String {
    return selectedUserPersonaProfile()
        ?.content
        ?.trim()
        ?: assistant?.userPersona.orEmpty().trim()
}

fun Settings.effectiveUserName(): String {
    return selectedUserPersonaProfile()
        ?.name
        ?.trim()
        ?: displaySetting.userNickname.trim()
}

fun Settings.effectiveUserAvatar(): Avatar {
    return selectedUserPersonaProfile()?.avatar ?: displaySetting.userAvatar
}
