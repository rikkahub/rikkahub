package me.rerere.rikkahub.voiceagent.telemetry

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSentryRuntimeConfigDiagnosticsTest {
    @Test
    fun `writes sentry runtime diagnostics without dsn value`() {
        val root = Files.createTempDirectory("voice-sentry-diagnostics").toFile()
        try {
            val diagnostics = VoiceSentryRuntimeConfigDiagnostics.fromConfig(
                dsn = "https://public@example.com/1",
                environment = "production",
                tracesSampleRate = 2.0,
                observability = "sentry",
            )

            writeVoiceSentryRuntimeConfigDiagnostics(root, diagnostics)

            val file = File(root, "voice-e2e/android-sentry-config.json")
            assertTrue(file.exists())
            val content = file.readText()
            assertFalse(content.contains("https://public@example.com/1"))

            val json = Json.parseToJsonElement(content).jsonObject
            assertEquals(
                setOf(
                    "dsnConfigured",
                    "environmentConfigured",
                    "tracesSampleRate",
                    "tracingEnabled",
                    "observability",
                ),
                json.keys,
            )
            assertEquals(true, json["dsnConfigured"]!!.jsonPrimitive.boolean)
            assertEquals(true, json["environmentConfigured"]!!.jsonPrimitive.boolean)
            assertEquals(1.0, json["tracesSampleRate"]!!.jsonPrimitive.double, 0.0)
            assertEquals(true, json["tracingEnabled"]!!.jsonPrimitive.boolean)
            assertEquals("sentry", json["observability"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `no-op config reports disabled sentry and noop observability`() {
        val diagnostics = VoiceSentryRuntimeConfigDiagnostics.fromConfig(
            dsn = "",
            environment = "",
            tracesSampleRate = -1.0,
            observability = "noop",
        )

        assertEquals(false, diagnostics.dsnConfigured)
        assertEquals(false, diagnostics.environmentConfigured)
        assertEquals(0.0, diagnostics.tracesSampleRate, 0.0)
        assertEquals(false, diagnostics.tracingEnabled)
        assertEquals("noop", diagnostics.observability)

        val json = Json.parseToJsonElement(diagnostics.toJson()).jsonObject
        assertEquals(false, json["dsnConfigured"]!!.jsonPrimitive.boolean)
        assertEquals(false, json["environmentConfigured"]!!.jsonPrimitive.boolean)
        assertEquals(0.0, json["tracesSampleRate"]!!.jsonPrimitive.double, 0.0)
        assertEquals(false, json["tracingEnabled"]!!.jsonPrimitive.boolean)
        assertEquals("noop", json["observability"]!!.jsonPrimitive.content)
    }
}
