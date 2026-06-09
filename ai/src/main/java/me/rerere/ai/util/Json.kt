package me.rerere.ai.util

import kotlinx.serialization.json.Json

@PublishedApi
internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
