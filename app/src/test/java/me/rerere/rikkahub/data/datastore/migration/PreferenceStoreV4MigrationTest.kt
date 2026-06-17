package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the v3 -> v4 DisplaySetting repair (audit C23):
 *  - the renamed field `showDateBelowName` -> `showDateTimeInMessage` must carry the user's value,
 *  - the flipped default of `skipCropImage` (false -> true) must be pinned to the pre-flip value
 *    (false) for existing installs that never recorded the key,
 *  - a fresh/empty install must not gain a spurious DisplaySetting.
 */
class PreferenceStoreV4MigrationTest {

    private fun obj(json: String): JsonObject = JsonInstant.parseToJsonElement(json).jsonObject

    @Test
    fun `renamed date field carries the old value across and drops the legacy key`() {
        val migrated = obj(migrateDisplaySettingJson("""{"showDateBelowName":true}"""))

        assertEquals(true, migrated["showDateTimeInMessage"]!!.jsonPrimitive.boolean)
        assertNull("legacy key removed", migrated["showDateBelowName"])
    }

    @Test
    fun `an existing new field is not clobbered by the legacy key`() {
        val migrated = obj(
            migrateDisplaySettingJson("""{"showDateBelowName":true,"showDateTimeInMessage":false}""")
        )

        assertEquals(false, migrated["showDateTimeInMessage"]!!.jsonPrimitive.boolean)
        assertNull(migrated["showDateBelowName"])
    }

    @Test
    fun `skipCropImage is pinned to the pre-flip default when absent`() {
        val migrated = obj(migrateDisplaySettingJson("""{"showModelIcon":true}"""))

        assertEquals(false, migrated["skipCropImage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an explicit skipCropImage value is preserved`() {
        val migrated = obj(migrateDisplaySettingJson("""{"skipCropImage":true}"""))

        assertEquals(true, migrated["skipCropImage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `unrelated fields are preserved`() {
        val migrated = obj(
            migrateDisplaySettingJson("""{"userNickname":"alex","showModelName":false,"showDateBelowName":true}""")
        )

        assertEquals("alex", migrated["userNickname"]!!.jsonPrimitive.content)
        assertEquals(false, migrated["showModelName"]!!.jsonPrimitive.boolean)
        assertEquals(true, migrated["showDateTimeInMessage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an empty object only gains the pinned skipCropImage`() {
        val migrated = obj(migrateDisplaySettingJson("{}"))

        assertEquals(false, migrated["skipCropImage"]!!.jsonPrimitive.boolean)
        assertFalse("no legacy key introduced", migrated.containsKey("showDateBelowName"))
    }

    @Test
    fun `malformed json is returned unchanged`() {
        assertEquals("not json", migrateDisplaySettingJson("not json"))
    }
}
