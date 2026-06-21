package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.LEGACY_BUILTIN_PROVIDER_IDS
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * v5 -> v6: provider catalog migration.
 *
 * The long tail of hardcoded built-in providers moved to the browseable catalog. Two things happen
 * once, here, rather than on every read:
 *
 * 1. **Seed the curated defaults** (Anthropic + OpenAI) when missing. This replaces the old
 *    "force-re-add every default on every decode" loop, so a default the user deletes now STAYS
 *    deleted instead of reappearing.
 * 2. **Drop pristine legacy built-ins.** A provider whose id is a removed built-in AND that the user
 *    never touched (no credentials of any kind, no models) is removed to de-clutter. A provider the
 *    user configured is KEPT — and because `builtIn` is @Transient (always false on decode now that
 *    nothing forces it true), it becomes a normal, deletable, user-owned provider.
 *
 * The pristine check runs on the raw provider JSON, not a decoded [me.rerere.ai.provider.ProviderSetting]:
 * `JsonInstant` has `ignoreUnknownKeys = true`, so a decode-then-inspect would silently drop any
 * credential field not modelled by the typed accessors and a configured provider could look empty and be
 * wrongly removed. Scanning the serialized object keeps the check robust against that.
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val existing: List<JsonObject> = currentData[SettingsStore.PROVIDERS]
            ?.let { runCatching { JsonInstant.parseToJsonElement(it).jsonArray }.getOrNull() }
            ?.filterIsInstance<JsonObject>()
            ?: emptyList()

        val kept = existing.filterNot { it.isPristineLegacyBuiltIn() }

        val presentIds = kept.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val seeds = DEFAULT_PROVIDERS
            .filter { it.id.toString() !in presentIds }
            .map { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as JsonObject }

        prefs[SettingsStore.PROVIDERS] = JsonArray(kept + seeds).toString()
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    /** A removed built-in the user never configured: same id family, no models, no credential anywhere. */
    private fun JsonObject.isPristineLegacyBuiltIn(): Boolean {
        val id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        if (id !in LEGACY_BUILTIN_PROVIDER_IDS) return false
        // The old RikkaHub gateway SHIPPED the "auto" model — it is not a user-added model, so a RikkaHub
        // provider that carries only it is still pristine (and must be dropped, else stale chat/fast/
        // translate/compress pointers keep resolving to the removed hosted gateway).
        val userModels = (this["models"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.filterNot { (it["id"] as? JsonPrimitive)?.contentOrNull == LEGACY_AUTO_MODEL_ID }
            .orEmpty()
        if (userModels.isNotEmpty()) return false
        if (CREDENTIAL_STRING_KEYS.any { (this[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }) {
            return false
        }
        if (CREDENTIAL_FLAG_KEYS.any { (this[it] as? JsonPrimitive)?.booleanOrNull == true }) return false
        return true
    }

    private companion object {
        // The model id the dropped RikkaHub gateway shipped its "auto" model under (old DEFAULT_AUTO_MODEL_ID).
        const val LEGACY_AUTO_MODEL_ID = "b7055fb4-39f9-4042-a88a-0d80ed76cf08"

        // Every credential surface across the OpenAI / Google / Anthropic wire adapters.
        val CREDENTIAL_STRING_KEYS = listOf(
            "apiKey", "privateKey", "serviceAccountEmail", "oauthToken",
        )
        val CREDENTIAL_FLAG_KEYS = listOf("vertexAI", "useServiceAccount")
    }
}
