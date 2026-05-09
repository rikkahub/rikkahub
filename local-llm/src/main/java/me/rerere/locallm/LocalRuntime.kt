package me.rerere.locallm

import kotlinx.serialization.Serializable

/**
 * Marker for the runtime types the app supports. Used as a discriminator in
 * [LocalRuntimePreferences] (the cached-accelerator key, the installed-models index)
 * and in any UI that wants to display a runtime-specific chip.
 *
 * Currently only LiteRT is shipped. Adding another runtime later means adding a case
 * here + a Provider implementation + a tile in Settings, and nothing else has to change.
 */
@Serializable
sealed class LocalRuntime(val displayName: String, val fileExtension: String) {
    @Serializable data object LiteRT : LocalRuntime(displayName = "LiteRT", fileExtension = "litertlm")
}
