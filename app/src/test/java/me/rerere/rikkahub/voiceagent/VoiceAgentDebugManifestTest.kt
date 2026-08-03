package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixture
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.debug.HermesTextDebugReceiver
import me.rerere.rikkahub.voiceagent.debug.VoiceAgentDebugSeedReceiver
import me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class VoiceAgentDebugManifestTest {
    @After
    fun tearDown() {
        VoiceCaptureFixtureArming.clearForTest()
    }

    @Test
    fun `debug receivers require shell held permission`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceCaptureFixtureDebugReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals("android.permission.DUMP", audioReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", seedReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", textReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", findService(".voiceagent.VoiceAgentCallService").getAttribute("android:permission"))
    }

    @Test
    fun `debug receivers remain exported for adb workflows`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceCaptureFixtureDebugReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals("true", audioReceiver.getAttribute("android:exported"))
        assertEquals("true", seedReceiver.getAttribute("android:exported"))
        assertEquals("true", textReceiver.getAttribute("android:exported"))
        assertEquals("true", findService(".voiceagent.VoiceAgentCallService").getAttribute("android:exported"))
    }

    @Test
    fun `debug receivers keep expected actions`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceCaptureFixtureDebugReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals(
            listOf(
                VoiceCaptureFixtureArming.ACTION_ARM_FIXTURE,
                STAGE_ACTION,
                VoiceCaptureFixtureArming.ACTION_TRIGGER_FIXTURE,
            ),
            audioReceiver.actionNames(),
        )
        assertEquals(
            listOf(VoiceAgentDebugSeedReceiver.ACTION_SEED_HERMES_PROVIDER),
            seedReceiver.actionNames(),
        )
        assertEquals(
            listOf(HermesTextDebugReceiver.ACTION_SEND_HERMES_TEXT),
            textReceiver.actionNames(),
        )
    }

    @Test
    fun `stage action remains debug only on the existing fixture receiver`() {
        val debugReceivers = manifestElements("src/debug/AndroidManifest.xml", "receiver")
            .filter {
                it.getAttribute("android:name") ==
                    ".voiceagent.debug.VoiceCaptureFixtureDebugReceiver"
            }
        val mainActions = manifestElements("src/main/AndroidManifest.xml", "action")
            .map { it.getAttribute("android:name") }

        assertEquals(1, debugReceivers.size)
        assertEquals(
            listOf(
                VoiceCaptureFixtureArming.ACTION_ARM_FIXTURE,
                STAGE_ACTION,
                VoiceCaptureFixtureArming.ACTION_TRIGGER_FIXTURE,
            ),
            debugReceivers.single().actionNames(),
        )
        assertEquals(STAGE_ACTION, VoiceCaptureFixtureArming.ACTION_STAGE_FIXTURE)
        assertFalse(mainActions.contains(STAGE_ACTION))
    }

    @Test
    fun `stage request uses bounded file and exact result contract`() = runTest {
        val filesDir = createTempDirectory("voice-fixture-files").toFile()
        val fixtureFile = File(filesDir, "voice-fixtures/request-2.pcm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val delays = mutableListOf<Long>()
        val source = VoiceCaptureFixtureArming.claim(token) { delays += it }.getOrThrow()
        val chunks = mutableListOf<List<Byte>>()
        val pump = async {
            source.pump(
                onPcm16 = { chunks += it.toList() },
                onFixtureComplete = {},
            )
        }

        val result = VoiceCaptureFixtureDebugReceiver().stage(
            context = context(filesDir),
            intent = stageIntent(
                token = token,
                path = "voice-fixtures/request-2.pcm",
                chunkBytes = 2,
                chunkDelayMs = 7,
            ),
        )
        fixtureFile.writeBytes(byteArrayOf(99, 99, 99, 99, 99))
        assertEquals("status=ok\naction=stage\naccepted=true", result)

        assertTrue(
            VoiceCaptureFixtureArming.trigger(token, "voice-fixtures/request-2.pcm").accepted,
        )
        source.awaitIdle()
        assertEquals(
            listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5)),
            chunks,
        )
        assertEquals(listOf(7L, 7L), delays)

        source.close()
        pump.await()
    }

    @Test
    fun `stage request rejects canonical path traversal`() {
        val parent = createTempDirectory("voice-fixture-boundary").toFile()
        val filesDir = File(parent, "files").apply { mkdirs() }
        File(parent, "outside.pcm").writeBytes(byteArrayOf(1, 2))
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()

        assertThrows(IllegalArgumentException::class.java) {
            VoiceCaptureFixtureDebugReceiver().stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = "../outside.pcm",
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                ),
            )
        }

        source.close()
    }

    private fun findReceiver(name: String): Element {
        return findManifestElement(tagName = "receiver", name = name)
    }

    private fun findService(name: String): Element {
        return findManifestElement(tagName = "service", name = name)
    }

    private fun findManifestElement(tagName: String, name: String): Element {
        return manifestElements("src/debug/AndroidManifest.xml", tagName)
            .first { it.getAttribute("android:name") == name }
    }

    private fun manifestElements(path: String, tagName: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val elements = document.getElementsByTagName(tagName)
        return (0 until elements.length).map { elements.item(it) as Element }
    }

    private fun Element.actionNames(): List<String> {
        val actions = getElementsByTagName("action")

        return (0 until actions.length)
            .map { actions.item(it) as Element }
            .map { it.getAttribute("android:name") }
    }

    private fun context(filesDir: File): Context = mockk {
        every { this@mockk.filesDir } returns filesDir
    }

    private fun stageIntent(
        token: String,
        path: String,
        chunkBytes: Int,
        chunkDelayMs: Long,
    ): Intent = mockk {
        every { getStringExtra("token") } returns token
        every { getStringExtra("path") } returns path
        every {
            getIntExtra("chunk_bytes", any())
        } returns chunkBytes
        every {
            getLongExtra("chunk_delay_ms", any())
        } returns chunkDelayMs
    }

    private fun fixture(path: String, bytes: ByteArray) = VoiceCaptureFixture(
        path = path,
        pcm16 = bytes,
        chunkBytes = 2,
        chunkDelayMs = 0,
    )

    private companion object {
        const val STAGE_ACTION =
            "me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE"
    }
}
