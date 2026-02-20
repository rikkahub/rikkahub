package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class RikkaRouterConfig(
    val enabled: Boolean = true,
    val groups: List<RikkaRouterGroup> = emptyList(),
)

@Serializable
data class RikkaRouterGroup(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val enabled: Boolean = true,
    val primaryModelId: Uuid? = null,
    val members: List<RikkaRouterMember> = emptyList(),
)

@Serializable
data class RikkaRouterMember(
    val modelId: Uuid,
    val enabled: Boolean = true,
    val order: Int = 0,
)
