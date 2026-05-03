package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import java.util.Locale
import kotlin.coroutines.resume

private sealed class RecognitionOutcome {
    data class Success(val text: String) : RecognitionOutcome()
    data class Error(val code: Int) : RecognitionOutcome()
}

fun speechToTextTool(context: Context): Tool = Tool(
    name = "speech_to_text",
    description = """
        Listen to the microphone and convert spoken words to text. The user must speak when
        prompted. A toast appears to indicate listening has started.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "BCP-47 language tag (default device locale, e.g. en-US, zh-CN)")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum time to listen in milliseconds (default 30000, min 1000, max 60000)")
                })
            }
        )
    },
    execute = {
        if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.RECORD_AUDIO))) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "permission RECORD_AUDIO not granted") }.toString()
                )
            )
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "speech recognizer unavailable") }.toString()
                )
            )
        }

        val params = it.jsonObject
        val languageParam = params["language"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 30_000)
            .coerceIn(1_000, 60_000)
        val lang = languageParam ?: Locale.getDefault().toLanguageTag()

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            Toast.makeText(
                context,
                R.string.assistant_page_local_tools_listening_toast,
                Toast.LENGTH_SHORT
            ).show()
        }

        val outcome = withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine { cont ->
                var recognizer: SpeechRecognizer? = null

                mainHandler.post {
                    val rec = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = rec

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }

                    rec.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}

                        override fun onResults(results: Bundle?) {
                            if (!cont.isActive) return
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                            if (text != null) {
                                cont.resume(RecognitionOutcome.Success(text))
                            } else {
                                cont.resume(RecognitionOutcome.Error(SpeechRecognizer.ERROR_NO_MATCH))
                            }
                        }

                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(RecognitionOutcome.Error(error))
                        }
                    })

                    rec.startListening(intent)
                }

                cont.invokeOnCancellation {
                    mainHandler.post {
                        try {
                            recognizer?.cancel()
                            recognizer?.destroy()
                        } catch (_: Throwable) {
                            // Best-effort cleanup.
                        }
                    }
                }
            }
        }

        val payload = when (outcome) {
            is RecognitionOutcome.Success -> buildJsonObject { put("text", outcome.text) }
            is RecognitionOutcome.Error -> {
                val errorName = when (outcome.code) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
                    SpeechRecognizer.ERROR_NETWORK -> "network_error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                    SpeechRecognizer.ERROR_SERVER -> "server_error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission RECORD_AUDIO not granted"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
                    SpeechRecognizer.ERROR_CLIENT -> "client_error"
                    else -> "no_match"
                }
                buildJsonObject { put("error", errorName) }
            }
            null -> buildJsonObject { put("error", "timeout") }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
