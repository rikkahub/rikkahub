package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.common.json.JsonInstant

/**
 * v3 -> v4: repair the persisted [me.rerere.rikkahub.data.datastore.DisplaySetting] after two
 * source changes that silently dropped a user's preference on upgrade:
 *
 *  1. the field `showDateBelowName` was renamed to `showDateTimeInMessage`. The stored JSON still
 *     carries the old key, which deserializes as an unknown field and the new field falls back to
 *     its default — silently losing the user's choice. Carry the old value across.
 *  2. `skipCropImage`'s default was flipped from `false` to `true`. An existing install whose stored
 *     DisplaySetting never recorded the key would suddenly inherit the new default on upgrade. Pin
 *     the pre-flip value (`false`) when the key is absent so an upgrade does not change behavior.
 *
 * Only an EXISTING DisplaySetting is rewritten — a fresh install (no DISPLAY_SETTING key) is left
 * untouched so new users get the current defaults (incl. `skipCropImage = true`).
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.DISPLAY_SETTING]?.let { json ->
            prefs[SettingsStore.DISPLAY_SETTING] = migrateDisplaySettingJson(json)
        }

        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * Rewrites a stored DisplaySetting JSON: rename `showDateBelowName` -> `showDateTimeInMessage`
 * (preserving the value), and pin `skipCropImage = false` when the key is absent (pre-flip default).
 * On any parse failure the input is returned unchanged.
 */
internal fun migrateDisplaySettingJson(json: String): String {
    return runCatching {
        val obj = JsonInstant.parseToJsonElement(json) as? JsonObject
            ?: return@runCatching json
        val mutable = obj.toMutableMap()

        val legacyDate = mutable["showDateBelowName"]
        if (legacyDate != null && "showDateTimeInMessage" !in mutable) {
            mutable["showDateTimeInMessage"] = legacyDate
        }
        mutable.remove("showDateBelowName")

        if ("skipCropImage" !in mutable) {
            mutable["skipCropImage"] = JsonPrimitive(false)
        }

        JsonInstant.encodeToString(JsonObject(mutable))
    }.getOrElse { json }
}
