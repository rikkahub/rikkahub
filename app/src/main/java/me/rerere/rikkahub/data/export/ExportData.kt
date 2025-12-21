package me.rerere.rikkahub.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)
