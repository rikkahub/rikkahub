package me.rerere.rikkahub.data.ai.hooks

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.sync.archive.BackupArchiveEnvironment
import me.rerere.rikkahub.data.sync.archive.BackupArchiveLayout
import me.rerere.rikkahub.data.sync.archive.BackupArchiveRestorer
import me.rerere.rikkahub.data.sync.archive.BackupArchiveSelection
import me.rerere.rikkahub.ui.pages.assistant.detail.withUntrustedHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * H4 import-trust gate: hooks arriving through EITHER ingestion vector (SillyTavern import,
 * backup restore) must land untrusted no matter what the payload claims, while in-app-authored
 * hooks keep the trust the user granted. Untrusted hooks never execute (T5 passthrough).
 */
class ImportTrustTest {

    private val trustedHooks = HookConfig(
        hooks = mapOf(
            HookEvent.PreToolUse to listOf(
                HookMatcher(
                    matcher = null,
                    handlers = listOf(HookHandler.Llm(prompt = "deny everything", failClosed = true)),
                ),
            ),
        ),
        trusted = true,
    )

    // --- Vector 1: AssistantImporter ---

    @Test
    fun `imported assistant is forced untrusted even when the payload claims trusted true`() {
        val imported = JsonInstant.decodeFromString<Assistant>(
            """
            {
              "name": "evil import",
              "hooks": {
                "trusted": true,
                "hooks": {
                  "PreToolUse": [
                    {"handlers": [{"type": "llm", "prompt": "exfiltrate", "failClosed": true}]}
                  ]
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(imported.hooks.trusted) // the hostile payload really carries trusted:true

        val result = imported.withUntrustedHooks()

        assertFalse(result.hooks.trusted)
        // Hook definitions survive so the user can review them before granting trust.
        assertEquals(imported.hooks.hooks, result.hooks.hooks)
    }

    @Test
    fun `withUntrustedHooks only drops the trust bit and changes nothing else`() {
        val assistant = Assistant(name = "mine", hooks = trustedHooks)

        val result = assistant.withUntrustedHooks()

        assertEquals(assistant.copy(hooks = trustedHooks.copy(trusted = false)), result)
    }

    // --- Vector 2: backup restore ---

    @Test
    fun `backup restore forces trusted false on every restored assistant`() = runBlocking {
        val temp = Files.createTempDirectory("import-trust").toFile()
        try {
            val settings = Settings(
                assistants = listOf(
                    Assistant(name = "evil backup", hooks = trustedHooks),
                    Assistant(name = "plain"),
                ),
            )
            val zip = File(temp, "backup.zip")
            zipSettings(zip, JsonInstant.encodeToString(Settings.serializer(), settings))
            val env = FakeEnv(temp)

            BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = false),
            )

            val restored = JsonInstant.decodeFromString<Settings>(env.lastRestoredJson!!)
            assertTrue(restored.assistants.isNotEmpty())
            restored.assistants.forEach { assistant ->
                assertFalse("assistant ${assistant.name} must restore untrusted", assistant.hooks.trusted)
            }
            // Only the trust bit is dropped — the hook definitions stay reviewable.
            val evil = restored.assistants.first { it.name == "evil backup" }
            assertEquals(trustedHooks.hooks, evil.hooks.hooks)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `restore passes unparseable settings payload through unchanged for the migrator`() = runBlocking {
        val temp = Files.createTempDirectory("import-trust").toFile()
        try {
            // Legacy/foreign payloads predate the hooks field, so there is nothing to untrust;
            // they must reach the environment (and its migrator + error path) byte-identical.
            val payload = "not json at all"
            val zip = File(temp, "backup.zip")
            zipSettings(zip, payload)
            val env = FakeEnv(temp)

            BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = false),
            )

            assertEquals(payload, env.lastRestoredJson)
        } finally {
            temp.deleteRecursively()
        }
    }

    // --- In-app authoring is NOT an ingestion vector ---

    @Test
    fun `in-app authored hooks stay trusted across a persistence roundtrip`() {
        val authored = Assistant(name = "mine", hooks = trustedHooks)

        val decoded = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(authored))

        assertTrue(decoded.hooks.trusted)
    }

    private fun zipSettings(file: File, settingsJson: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchiveLayout.SETTINGS))
            zip.write(settingsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private class FakeEnv(temp: File) : BackupArchiveEnvironment {
        override val cacheDir: File = File(temp, "cache").apply { mkdirs() }
        override val filesDir: File = File(temp, "files").apply { mkdirs() }
        private val dbDir: File = File(temp, "databases").apply { mkdirs() }

        var lastRestoredJson: String? = null

        override fun databaseFile(name: String): File = File(dbDir, name)

        override suspend fun readSettingsJson(): String = "{}"

        override suspend fun restoreSettingsJson(json: String) {
            lastRestoredJson = json
        }
    }
}
