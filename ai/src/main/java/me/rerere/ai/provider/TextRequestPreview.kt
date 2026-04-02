package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TextRequestHeader(
    val name: String,
    val value: String,
)

@Serializable
data class TextRequestPreview(
    val providerName: String,
    val apiName: String,
    val method: String = "POST",
    val url: String,
    val stream: Boolean,
    val headers: List<TextRequestHeader>,
    val body: JsonObject,
)
