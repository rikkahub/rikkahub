package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.TagType

/**
 * A user-facing precondition the provider needs before it can actually run inference. Rendered
 * as a tag on the provider card so users know what's missing without having to open the
 * detail page.
 *
 * Add new conditions by extending [ProviderRequirement.from] for the relevant subtype — the
 * card pulls the list automatically.
 */
data class ProviderRequirement(
    val label: String,
    val severity: TagType,
) {
    companion object {
        /**
         * Returns the user-visible preconditions for the given provider. Empty list = nothing
         * special required (typical OpenAI / Google / Claude / custom-OpenAI-compatible
         * providers — everything is server-side, just an API key).
         */
        fun from(provider: ProviderSetting): List<ProviderRequirement> = when (provider) {
            is ProviderSetting.AICore -> listOf(
                ProviderRequirement(
                    label = "Requires AICore beta",
                    severity = TagType.WARNING,
                ),
                ProviderRequirement(
                    label = "Pixel 8 / 9 / 10",
                    severity = TagType.INFO,
                ),
            )
            is ProviderSetting.LiteRtLocal -> listOf(
                ProviderRequirement(
                    label = "On-device · ~1.5 GB",
                    severity = TagType.INFO,
                ),
            )
            is ProviderSetting.LlamaCppLocal -> listOf(
                ProviderRequirement(
                    label = "On-device · ~1 GB",
                    severity = TagType.INFO,
                ),
            )
            else -> emptyList()
        }
    }
}
