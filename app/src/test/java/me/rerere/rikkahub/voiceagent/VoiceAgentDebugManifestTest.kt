package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.content.Intent
import android.system.OsConstants
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixture
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.debug.HermesTextDebugReceiver
import me.rerere.rikkahub.voiceagent.debug.OpenPcmDescriptor
import me.rerere.rikkahub.voiceagent.debug.PcmDescriptorOpener
import me.rerere.rikkahub.voiceagent.debug.PcmDescriptorStat
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

        val descriptorOpener = JvmPcmDescriptorOpener()
        val result = VoiceCaptureFixtureDebugReceiver(descriptorOpener).stage(
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
        assertEquals(listOf(EXPECTED_PCM_OPEN_FLAGS), descriptorOpener.openedFlags)
        assertEquals(1, descriptorOpener.statCalls)

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
            receiver().stage(
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

    @Test
    fun `stage request rejects fixture content that does not match the immutable snapshot`() {
        val filesDir = createTempDirectory("voice-fixture-integrity").toFile()
        File(filesDir, "voice-fixtures/request-2.pcm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(99, 99, 99, 99, 99))
        }
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()

        assertThrows(IllegalArgumentException::class.java) {
            receiver().stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = "voice-fixtures/request-2.pcm",
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                    expectedBytes = byteArrayOf(1, 2, 3, 4, 5),
                ),
            )
        }

        source.close()
    }

    @Test
    fun `stage request does not follow a substituted fixture symlink`() {
        val filesDir = createTempDirectory("voice-fixture-nofollow").toFile()
        val fixtureDir = File(filesDir, "voice-fixtures").apply { mkdirs() }
        val expectedBytes = byteArrayOf(1, 2, 3, 4, 5)
        val replacement = File(fixtureDir, "replacement.pcm").apply { writeBytes(expectedBytes) }
        Files.createSymbolicLink(File(fixtureDir, "request-2.pcm").toPath(), replacement.toPath())
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()

        assertThrows(Exception::class.java) {
            receiver().stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = "voice-fixtures/request-2.pcm",
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                    expectedBytes = expectedBytes,
                ),
            )
        }

        source.close()
    }

    @Test(timeout = 2_000)
    fun `stage request rejects FIFO substituted at descriptor open boundary without reading`() {
        val filesDir = createTempDirectory("voice-fixture-fifo-race").toFile()
        val fixtureFile = File(filesDir, "voice-fixtures/request-2.pcm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val relativePath = fixtureFile.relativeTo(filesDir).path
        val descriptorOpener = JvmPcmDescriptorOpener { candidate ->
            Files.move(candidate, candidate.resolveSibling("request-2.original"))
            val mkfifo = ProcessBuilder("mkfifo", candidate.toString())
                .redirectErrorStream(true)
                .start()
            val exitCode = mkfifo.waitFor()
            assertEquals(mkfifo.inputStream.bufferedReader().readText(), 0, exitCode)
        }

        assertThrows(IllegalArgumentException::class.java) {
            VoiceCaptureFixtureDebugReceiver(descriptorOpener).stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = relativePath,
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                ),
            )
        }

        assertEquals(listOf(EXPECTED_PCM_OPEN_FLAGS), descriptorOpener.openedFlags)
        assertEquals(1, descriptorOpener.statCalls)
        assertEquals(0, descriptorOpener.readCalls)
        assertFalse(VoiceCaptureFixtureArming.trigger(token, relativePath).accepted)

        source.close()
    }

    @Test
    fun `stage request rejects multiply linked fixture before reading`() {
        val filesDir = createTempDirectory("voice-fixture-hardlink").toFile()
        val fixtureFile = File(filesDir, "voice-fixtures/request-2.pcm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        Files.createLink(fixtureFile.toPath().resolveSibling("request-2.alias"), fixtureFile.toPath())
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val relativePath = fixtureFile.relativeTo(filesDir).path
        val descriptorOpener = JvmPcmDescriptorOpener()

        assertThrows(IllegalArgumentException::class.java) {
            VoiceCaptureFixtureDebugReceiver(descriptorOpener).stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = relativePath,
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                ),
            )
        }

        assertEquals(1, descriptorOpener.statCalls)
        assertEquals(0, descriptorOpener.readCalls)
        assertFalse(VoiceCaptureFixtureArming.trigger(token, relativePath).accepted)

        source.close()
    }

    @Test
    fun `stage request rejects an oversized declaration before opening the fixture`() {
        val filesDir = createTempDirectory("voice-fixture-size-bound").toFile()
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(9, 10)),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val descriptorOpener = JvmPcmDescriptorOpener()

        assertThrows(IllegalArgumentException::class.java) {
            VoiceCaptureFixtureDebugReceiver(descriptorOpener).stage(
                context = context(filesDir),
                intent = stageIntent(
                    token = token,
                    path = "voice-fixtures/request-2.pcm",
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                    expectedSize = 16_777_217L,
                ),
            )
        }

        assertTrue(descriptorOpener.openedFlags.isEmpty())
        assertEquals(0, descriptorOpener.readCalls)
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

    private fun receiver(): VoiceCaptureFixtureDebugReceiver =
        VoiceCaptureFixtureDebugReceiver(JvmPcmDescriptorOpener())

    private fun stageIntent(
        token: String,
        path: String,
        chunkBytes: Int,
        chunkDelayMs: Long,
        expectedBytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5),
        expectedSize: Long = expectedBytes.size.toLong(),
    ): Intent = mockk {
        every { getStringExtra("token") } returns token
        every { getStringExtra("path") } returns path
        every {
            getIntExtra("chunk_bytes", any())
        } returns chunkBytes
        every {
            getLongExtra("chunk_delay_ms", any())
        } returns chunkDelayMs
        every { getLongExtra("expected_size", any()) } returns expectedSize
        every { getStringExtra("expected_sha256") } returns expectedBytes.sha256()
    }

    private fun fixture(path: String, bytes: ByteArray) = VoiceCaptureFixture(
        path = path,
        pcm16 = bytes,
        chunkBytes = 2,
        chunkDelayMs = 0,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(prefix = "sha256:", separator = "") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val STAGE_ACTION =
            "me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE"
        val EXPECTED_PCM_OPEN_FLAGS: Int =
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
    }

    private class JvmPcmDescriptorOpener(
        private var beforeOpen: ((Path) -> Unit)? = null,
    ) : PcmDescriptorOpener {
        val openedFlags = mutableListOf<Int>()
        var statCalls = 0
            private set
        var readCalls = 0
            private set

        override fun open(path: String, flags: Int): OpenPcmDescriptor {
            openedFlags += flags
            val candidate = Path.of(path)
            beforeOpen?.also {
                beforeOpen = null
                it(candidate)
            }
            val mode = Files.getAttribute(
                candidate,
                "unix:mode",
                LinkOption.NOFOLLOW_LINKS,
            ) as Int
            val linkCount = (Files.getAttribute(
                candidate,
                "unix:nlink",
                LinkOption.NOFOLLOW_LINKS,
            ) as Number).toLong()
            val size = (Files.getAttribute(
                candidate,
                "unix:size",
                LinkOption.NOFOLLOW_LINKS,
            ) as Number).toLong()
            val channel = if ((mode and UNIX_FILE_TYPE_MASK) == UNIX_REGULAR_FILE) {
                Files.newByteChannel(
                    candidate,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } else {
                null
            }
            return object : OpenPcmDescriptor {
                override fun stat(): PcmDescriptorStat {
                    statCalls += 1
                    return PcmDescriptorStat(mode = mode, linkCount = linkCount, size = size)
                }

                override fun read(buffer: ByteArray, offset: Int, byteCount: Int): Int {
                    readCalls += 1
                    val read = requireNotNull(channel).read(ByteBuffer.wrap(buffer, offset, byteCount))
                    return if (read < 0) 0 else read
                }

                override fun close() {
                    channel?.close()
                }
            }
        }

        private companion object {
            const val UNIX_FILE_TYPE_MASK = 0xf000
            const val UNIX_REGULAR_FILE = 0x8000
        }
    }
}
