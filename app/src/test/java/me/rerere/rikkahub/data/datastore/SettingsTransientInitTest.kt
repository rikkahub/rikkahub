package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsTransientInitTest {

    @Test
    fun `init guard flag is not serialized into settings backup`() {
        // The dummy guard (init = true) seeds settingsFlow before real settings load and is
        // what whole-Settings backups (S3Sync/WebDavSync) serialize. If init leaks into the
        // JSON, restoring the backup decodes init = true and update() refuses to persist.
        val dummy = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings.dummy()))
        assertFalse(
            "Settings.init must be @kotlinx.serialization.Transient (absent from JSON)",
            dummy.jsonObject.containsKey("init"),
        )

        val default = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings()))
        assertFalse(
            "Settings.init must be @kotlinx.serialization.Transient (absent from JSON)",
            default.jsonObject.containsKey("init"),
        )
    }
}
