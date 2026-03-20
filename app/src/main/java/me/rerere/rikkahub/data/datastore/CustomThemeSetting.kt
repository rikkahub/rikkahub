package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class CustomThemeSetting(
    val enabled: Boolean = false,
    val autoGenerateOnColors: Boolean = false,
    val light: String = "",
    val dark: String = "",
)
