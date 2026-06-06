package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioDebugInjector

class VoiceAudioDebugInjectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoiceAudioDebugInjector.ACTION_INJECT_PCM) return

        val pendingResult = goAsync()
        thread(name = "voice-audio-debug-injection") {
            try {
                val file = resolvePcmFile(context, intent.getStringExtra(VoiceAudioDebugInjector.EXTRA_PATH))
                val chunkBytes = intent.getIntExtra(
                    VoiceAudioDebugInjector.EXTRA_CHUNK_BYTES,
                    VoiceAudioDebugInjector.DEFAULT_CHUNK_BYTES,
                )
                val chunkDelayMs = intent.getLongExtra(
                    VoiceAudioDebugInjector.EXTRA_CHUNK_DELAY_MS,
                    VoiceAudioDebugInjector.DEFAULT_CHUNK_DELAY_MS,
                )
                val leadingSilenceMs = intent.getLongExtra(
                    VoiceAudioDebugInjector.EXTRA_LEADING_SILENCE_MS,
                    VoiceAudioDebugInjector.DEFAULT_LEADING_SILENCE_MS,
                )
                val trailingSilenceMs = intent.getLongExtra(
                    VoiceAudioDebugInjector.EXTRA_TRAILING_SILENCE_MS,
                    VoiceAudioDebugInjector.DEFAULT_TRAILING_SILENCE_MS,
                )
                val result = VoiceAudioDebugInjector.injectPcm16(
                    pcm16 = file.readBytes(),
                    chunkBytes = chunkBytes,
                    chunkDelayMs = chunkDelayMs,
                    leadingSilenceMs = leadingSilenceMs,
                    trailingSilenceMs = trailingSilenceMs,
                )
                Log.i(
                    TAG,
                    "debug_audio_injection result delivered=${result.delivered} " +
                        "bytes=${result.bytes} chunks=${result.chunkCount} " +
                        "leadingSilenceMs=$leadingSilenceMs trailingSilenceMs=$trailingSilenceMs " +
                        "message=${result.message}",
                )
            } catch (error: Throwable) {
                Log.e(TAG, "debug_audio_injection failed: ${error.message ?: error.javaClass.simpleName}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resolvePcmFile(context: Context, rawPath: String?): File {
        val path = rawPath?.trim().orEmpty()
        require(path.isNotBlank()) { "Missing ${VoiceAudioDebugInjector.EXTRA_PATH}" }

        val candidate = File(path).let {
            if (it.isAbsolute) it else File(context.filesDir, path)
        }.canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        require(candidate.path == filesRoot.path || candidate.path.startsWith(filesRoot.path + File.separator)) {
            "PCM path must be inside app files dir"
        }
        require(candidate.isFile) { "PCM file does not exist: ${candidate.path}" }
        return candidate
    }

    private companion object {
        const val TAG = "VoiceAudioDebugInjection"
    }
}
