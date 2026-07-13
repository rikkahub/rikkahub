package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.DebugHttpResponseEvidenceStore
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext

class HermesTextDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_HERMES_TEXT) return

        val pendingResult = goAsync()
        thread(name = "hermes-text-debug-e2e") {
            DebugHttpResponseEvidenceStore.clear()
            val startedAtMs = System.currentTimeMillis()

            val result = try {
                runBlocking {
                    execute(intent = intent, startedAtMs = startedAtMs)
                }
            } catch (_: TimeoutCancellationException) {
                HermesTextDebugResult.Failure(HermesTextDebugFailure.Timeout)
            } catch (_: InvalidConversationIdException) {
                HermesTextDebugResult.Failure(HermesTextDebugFailure.InvalidConversationId)
            } catch (_: MissingInputException) {
                HermesTextDebugResult.Failure(HermesTextDebugFailure.MissingInput)
            } catch (_: Throwable) {
                HermesTextDebugResult.Failure(HermesTextDebugFailure.Runtime)
            }

            try {
                if (result is HermesTextDebugResult.Success) {
                    Log.i(TAG, result.toLogLine())
                } else {
                    Log.e(TAG, result.toLogLine())
                }
            } finally {
                DebugHttpResponseEvidenceStore.clear()
                pendingResult.finish()
            }
        }
    }

    private suspend fun execute(intent: Intent, startedAtMs: Long): HermesTextDebugResult {
        val conversationId = intent.requiredExtra(EXTRA_CONVERSATION_ID).let { raw ->
            try {
                Uuid.parse(raw)
            } catch (_: IllegalArgumentException) {
                throw InvalidConversationIdException()
            }
        }
        val prompt = intent.requiredExtra(EXTRA_PROMPT)
        val expectedAnswer = intent.requiredExtra(EXTRA_EXPECTED_ANSWER)
        val chatService = GlobalContext.get().get<ChatService>()

        val completedConversation = coroutineScope {
            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(GENERATION_TIMEOUT_MS) {
                    chatService.generationDoneFlow.first { it == conversationId }
                }
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    chatService.sendMessage(
                        conversationId = conversationId,
                        content = listOf(UIMessagePart.Text(prompt)),
                    )
                }
                completion.await()
            } finally {
                completion.cancel()
            }
        }

        check(completedConversation == conversationId)
        val finalAnswer = chatService.getConversationFlow(conversationId)
            .value
            .currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString(separator = "") { it.text }
            ?: return HermesTextDebugResult.Failure(HermesTextDebugFailure.WrongAnswer)
        if (finalAnswer != expectedAnswer) {
            return HermesTextDebugResult.Failure(HermesTextDebugFailure.WrongAnswer)
        }

        val request = DebugHttpResponseEvidenceStore.snapshot()
            .firstOrNull { log ->
                log.timestamp >= startedAtMs &&
                    log.method == "POST" &&
                    log.endpointPath.endsWith("/chat/completions")
            }
            ?: return HermesTextDebugResult.Failure(HermesTextDebugFailure.HttpEvidenceMissing)
        val status = request.responseCode
            ?: return HermesTextDebugResult.Failure(HermesTextDebugFailure.HttpEvidenceMissing)
        if (status != 200) {
            return HermesTextDebugResult.Failure(HermesTextDebugFailure.HttpStatus)
        }
        return HermesTextDebugResult.Success(httpStatus = status, requestOrigin = request.origin)
    }

    private fun Intent.requiredExtra(name: String): String =
        getStringExtra(name)?.takeIf { it.isNotBlank() } ?: throw MissingInputException()

    private class MissingInputException : IllegalArgumentException()
    private class InvalidConversationIdException : IllegalArgumentException()

    companion object {
        const val ACTION_SEND_HERMES_TEXT = "me.rerere.rikkahub.debug.voiceagent.SEND_HERMES_TEXT"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_EXPECTED_ANSWER = "expected_answer"
        private const val GENERATION_TIMEOUT_MS = 180_000L
        private const val TAG = "HermesTextDebugE2E"
    }
}
