package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
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
        val initial = fixture(
            context = context,
            path = requireNotNull(intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_INITIAL_PATH)),
            chunkBytes = chunkBytes,
            chunkDelayMs = chunkDelayMs,
        )
        val staged = intent.getStringExtra(VoiceCaptureFixtureArming.EXTRA_STAGED_PATH)
            ?.let { listOf(fixture(context, it, chunkBytes, chunkDelayMs)) }
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
    ): VoiceCaptureFixture {
        val file = resolvePcmFile(context, path)
        return VoiceCaptureFixture(
            path = path,
            pcm16 = file.readBytes(),
            chunkBytes = chunkBytes,
            chunkDelayMs = chunkDelayMs,
        )
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
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
    }
}
