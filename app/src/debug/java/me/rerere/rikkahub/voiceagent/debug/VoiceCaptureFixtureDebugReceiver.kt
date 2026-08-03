package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixture
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming

class VoiceCaptureFixtureDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = runCatching {
            when (intent.action) {
                VoiceCaptureFixtureArming.ACTION_ARM_FIXTURE -> arm(context, intent)
                VoiceCaptureFixtureArming.ACTION_STAGE_FIXTURE -> stage(context, intent)
                VoiceCaptureFixtureArming.ACTION_TRIGGER_FIXTURE -> trigger(intent)
                else -> return
            }
        }
        result.onSuccess { data ->
            setResult(RESULT_OK, data, null)
        }.onFailure {
            setResult(RESULT_ERROR, "status=error\nerror=invalid_request", null)
        }
    }

    private fun arm(context: Context, intent: Intent): String {
        val chunkBytes = intent.getIntExtra(
            VoiceCaptureFixtureArming.EXTRA_CHUNK_BYTES,
            VoiceCaptureFixtureArming.DEFAULT_CHUNK_BYTES,
        )
        val chunkDelayMs = intent.getLongExtra(
            VoiceCaptureFixtureArming.EXTRA_CHUNK_DELAY_MS,
            VoiceCaptureFixtureArming.DEFAULT_CHUNK_DELAY_MS,
        )
        val expectedSize = intent.getLongExtra(VoiceCaptureFixtureArming.EXTRA_EXPECTED_SIZE, -1)
        val expectedSha256 = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_EXPECTED_SHA256)
        val initial = fixture(
            context = context,
            path = requireNotNull(intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_INITIAL_PATH)),
            chunkBytes = chunkBytes,
            chunkDelayMs = chunkDelayMs,
            expectedSize = expectedSize,
            expectedSha256 = expectedSha256,
        )
        val staged = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_STAGED_PATH)
            ?.let {
                listOf(fixture(context, it, chunkBytes, chunkDelayMs, expectedSize, expectedSha256))
            }
            .orEmpty()
        val token = VoiceCaptureFixtureArming.arm(initial, staged)
        return "status=ok\naction=arm\ntoken=$token"
    }

    internal fun stage(context: Context, intent: Intent): String {
        val token = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_TOKEN)?.trim().orEmpty()
        val path = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_PATH)?.trim().orEmpty()
        require(token.isNotEmpty() && path.isNotEmpty())
        val fixture = fixture(
            context = context,
            path = path,
            chunkBytes = intent.getIntExtra(
                VoiceCaptureFixtureArming.EXTRA_CHUNK_BYTES,
                VoiceCaptureFixtureArming.DEFAULT_CHUNK_BYTES,
            ),
            chunkDelayMs = intent.getLongExtra(
                VoiceCaptureFixtureArming.EXTRA_CHUNK_DELAY_MS,
                VoiceCaptureFixtureArming.DEFAULT_CHUNK_DELAY_MS,
            ),
            expectedSize = intent.getLongExtra(VoiceCaptureFixtureArming.EXTRA_EXPECTED_SIZE, -1),
            expectedSha256 = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_EXPECTED_SHA256),
        )
        val result = VoiceCaptureFixtureArming.stage(token, fixture)
        require(result.accepted)
        return "status=ok\naction=stage\naccepted=true"
    }

    private fun trigger(intent: Intent): String {
        val token = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_TOKEN)?.trim().orEmpty()
        val path = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_PATH)?.trim().orEmpty()
        require(token.isNotEmpty() && path.isNotEmpty())
        val result = VoiceCaptureFixtureArming.trigger(token, path)
        require(result.accepted)
        return "status=ok\naction=trigger\naccepted=true"
    }

    private fun fixture(
        context: Context,
        path: String,
        chunkBytes: Int,
        chunkDelayMs: Long,
        expectedSize: Long,
        expectedSha256: String?,
    ): VoiceCaptureFixture {
        require((expectedSize == -1L) == (expectedSha256 == null))
        val pcm16 = if (expectedSize == -1L) {
            resolvePcmFile(context, path).readBytes()
        } else {
            readVerifiedPcm(context, path, expectedSize, expectedSha256)
        }
        return VoiceCaptureFixture(
            path = path,
            pcm16 = pcm16,
            chunkBytes = chunkBytes,
            chunkDelayMs = chunkDelayMs,
        )
    }

    private fun readVerifiedPcm(
        context: Context,
        rawPath: String,
        expectedSize: Long,
        expectedSha256: String?,
    ): ByteArray {
        require(expectedSize in 1..Int.MAX_VALUE.toLong())
        require(expectedSha256?.matches(SHA256_PATTERN) == true)
        val path = rawPath.trim()
        require(path.isNotEmpty())
        val requested = File(path).let {
            if (it.isAbsolute) it else File(context.filesDir, path)
        }
        val filesRoot = context.filesDir.canonicalFile
        val parent = requireNotNull(requested.parentFile).canonicalFile
        require(parent.path == filesRoot.path || parent.path.startsWith(filesRoot.path + File.separator))
        require(requested.name !in setOf("", ".", ".."))
        val candidate = File(parent, requested.name).toPath()
        val attributes = Files.readAttributes(
            candidate,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink)
        require(attributes.size() == expectedSize)
        val bytes = ByteArray(expectedSize.toInt())
        Files.newByteChannel(candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == expectedSize)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                require(channel.read(buffer) >= 0)
            }
            require(channel.read(ByteBuffer.allocate(1)) == -1)
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(prefix = "sha256:", separator = "") { "%02x".format(it.toInt() and 0xff) }
        require(actualSha256 == expectedSha256)
        return bytes
    }

    private fun resolvePcmFile(context: Context, rawPath: String): File {
        val path = rawPath.trim()
        require(path.isNotEmpty())
        val candidate = File(path).let {
            if (it.isAbsolute) it else File(context.filesDir, path)
        }.canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        require(candidate.path == filesRoot.path || candidate.path.startsWith(filesRoot.path + File.separator))
        require(candidate.isFile)
        return candidate
    }

    private companion object {
        val SHA256_PATTERN = Regex("sha256:[0-9a-f]{64}")
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
    }
}
