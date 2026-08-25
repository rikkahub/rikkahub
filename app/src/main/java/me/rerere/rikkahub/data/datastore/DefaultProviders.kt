package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

/** Providers are user-managed; no provider templates are installed by default. */
val DEFAULT_PROVIDERS: List<ProviderSetting> = emptyList()
