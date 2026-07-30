package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentLoopWatchdogTest {
    @Test
    fun `descriptor timeout becomes structured tool timeout`() = runBlocking {
        val descriptor = ToolDescriptorRegistry.descriptorFor("workspace_read_file").copy(timeoutMillis = 20)

        val result = executeToolWithWatchdog(descriptor, defaultTimeoutMillis = 5_000) {
            delay(200)
            "late"
        }

        assertTrue(result is ToolWatchdogOutcome.TimedOut)
        assertEquals(20, (result as ToolWatchdogOutcome.TimedOut).timeoutMillis)
        val payload = toolFailureOutput(Json, TOOL_TIMEOUT_CODE, "trace").single().let {
            Json.parseToJsonElement((it as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject
        }
        assertEquals(TOOL_TIMEOUT_CODE, payload.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `parent cancellation is never converted to tool timeout`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val descriptor = ToolDescriptorRegistry.descriptorFor("explore_subagent")
        val deferred = async {
            executeToolWithWatchdog(descriptor, defaultTimeoutMillis = 5_000) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        deferred.cancel(CancellationException("parent"))
        try {
            deferred.await()
            fail("Expected parent cancellation")
        } catch (error: CancellationException) {
            assertEquals("parent", error.message)
        }
    }

    @Test
    fun `late tool result is not published when finish CAS fails`() = runBlocking {
        val runtime = object : AgentRunRuntime by NoOpAgentRunRuntime {
            override suspend fun toolFinished(
                executionId: String?,
                status: ToolExecutionStatus,
                output: List<me.rerere.ai.ui.UIMessagePart>,
                error: String?,
                artifact: me.rerere.rikkahub.data.artifacts.ToolArtifactReference?,
            ) = false
        }
        var published = false

        val committed = commitToolResult(runtime, "execution", ToolExecutionStatus.SUCCEEDED) {
            published = true
        }

        assertFalse(committed)
        assertFalse(published)
    }

    @Test
    fun `cancellation convergence completes before original cancellation escapes`() = runBlocking {
        val converged = CompletableDeferred<Unit>()
        val runtime = object : AgentRunRuntime by NoOpAgentRunRuntime {
            override suspend fun cancelled() {
                delay(10)
                converged.complete(Unit)
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                convergeCancelledRun(runtime, error)
            }
        }

        job.cancelAndJoin()

        assertTrue(converged.isCompleted)
    }

    @Test
    fun `provider idle watchdog resets after each chunk`() = runBlocking {
        val values = flow {
            delay(50)
            emit(1)
            delay(50)
            emit(2)
            delay(50)
            emit(3)
        }.withProviderIdleWatchdog(80).toList()

        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun `provider idle watchdog fails a stream with no progress`() = runBlocking {
        try {
            flow {
                delay(200)
                emit(1)
            }.withProviderIdleWatchdog(20).first()
            fail("Expected provider idle timeout")
        } catch (error: ProviderIdleTimeoutException) {
            assertEquals(20, error.timeoutMillis)
            assertEquals(PROVIDER_IDLE_TIMEOUT_CODE, error.message)
        }
    }
}
