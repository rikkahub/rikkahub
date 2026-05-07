package me.rerere.rikkahub.skills

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pure-JVM tests for [SkillTestRunner]. Exercises the runner via a fake [Driver] —
 * production wiring through Koin + ChatService is exercised on-device.
 */
class SkillTestRunnerTest {

    private val skillBodies = mapOf(
        "happy-skill" to "---\nname: happy-skill\ndescription: returns ok\n---\n# Happy",
    )
    private val reader: (String) -> String? = { name -> skillBodies[name] }

    private class FakeDriver(
        private val finishImmediately: Boolean = true,
        private val harvestText: String = "ok response",
        private val harvestImages: List<String> = emptyList(),
        private val throwOnSend: Throwable? = null,
        private val sleepBeforeFinishMs: Long = 0,
    ) : SkillTestRunner.Driver {
        var sentParts: List<UIMessagePart>? = null
        var startCalled = false
        var cleanupCalled = false

        override suspend fun currentAssistantId(): Uuid = Uuid.random()

        override suspend fun startConversation(conv: Conversation) {
            startCalled = true
        }

        override fun send(conv: Conversation, parts: List<UIMessagePart>) {
            if (throwOnSend != null) throw throwOnSend
            sentParts = parts
        }

        override suspend fun awaitGenerationDone(conversationId: Uuid, timeoutMs: Long): Boolean {
            if (sleepBeforeFinishMs > 0) {
                // Honour the caller's timeoutMs — same contract as the production driver
                // (which wraps the wait in withTimeoutOrNull). If sleepBeforeFinishMs is
                // longer than the timeout, return false (timed out); otherwise sleep
                // through and return finishImmediately.
                val effective = minOf(sleepBeforeFinishMs, timeoutMs)
                delay(effective)
                if (sleepBeforeFinishMs > timeoutMs) return false
            }
            return finishImmediately
        }

        override suspend fun harvest(conversationId: Uuid): SkillTestRunner.HarvestResult =
            SkillTestRunner.HarvestResult(harvestText, harvestImages)

        override suspend fun cleanup(conv: Conversation) {
            cleanupCalled = true
        }
    }

    // 1) Happy path
    @Test
    fun `happy path emits Running then Done with harvested text`() = runBlocking {
        val driver = FakeDriver(harvestText = "Hello, world.")
        val runner = SkillTestRunner(driver = driver, skillBodyReader = reader)
        val states = runner.runOnce("happy-skill", "do something").toList()
        assertEquals("expected 2 states (Running + Done), got $states", 2, states.size)
        assertTrue(states[0] is SkillTestRunner.TestRunState.Running)
        val done = states[1] as SkillTestRunner.TestRunState.Done
        assertEquals("Hello, world.", done.text)
        assertTrue("driver.startConversation should have been called", driver.startCalled)
        assertTrue("driver.send should have received parts", driver.sentParts != null)
        assertTrue("driver.cleanup should have run", driver.cleanupCalled)
        // The composed user message should mention the skill name and the user prompt.
        val sent = (driver.sentParts!!.first() as UIMessagePart.Text).text
        assertTrue("expected skill name in composed message: $sent", sent.contains("happy-skill"))
        assertTrue("expected prompt in composed message: $sent", sent.contains("do something"))
    }

    // 2) Timeout path
    @Test
    fun `timeout emits Running then Error tester_timeout`() = runBlocking {
        // 100ms timeout; driver sleeps 250ms then would return true. Runner should give
        // up at 100ms and surface tester_timeout.
        val driver = FakeDriver(finishImmediately = true, sleepBeforeFinishMs = 250)
        val runner = SkillTestRunner(
            driver = driver,
            skillBodyReader = reader,
            timeoutMs = 100,
        )
        val states = runner.runOnce("happy-skill", "do something").toList()
        assertEquals(2, states.size)
        assertTrue(states[0] is SkillTestRunner.TestRunState.Running)
        val err = states[1] as SkillTestRunner.TestRunState.Error
        assertEquals("tester_timeout", err.error)
        assertTrue("cleanup should still run on timeout", driver.cleanupCalled)
    }

    // 3) Driver-throws path — error envelope forwarded to caller.
    @Test
    fun `driver throws emits Running then Error with class name`() = runBlocking {
        val driver = FakeDriver(throwOnSend = IllegalStateException("backend offline"))
        val runner = SkillTestRunner(driver = driver, skillBodyReader = reader)
        val states = runner.runOnce("happy-skill", "do something").toList()
        assertEquals(2, states.size)
        val err = states[1] as SkillTestRunner.TestRunState.Error
        assertEquals("IllegalStateException", err.error)
        assertEquals("backend offline", err.detail)
        assertTrue("cleanup should still run on error", driver.cleanupCalled)
    }

    @Test
    fun `missing skill body short-circuits with missing_skill error`() = runBlocking {
        val driver = FakeDriver()
        val runner = SkillTestRunner(driver = driver, skillBodyReader = { null })
        val states = runner.runOnce("nonexistent", "anything").toList()
        assertEquals(1, states.size)
        val err = states[0] as SkillTestRunner.TestRunState.Error
        assertEquals("missing_skill", err.error)
        assertTrue("driver should never have been called", !driver.startCalled)
    }

    @Test
    fun `blank prompt short-circuits with empty_prompt error`() = runBlocking {
        val driver = FakeDriver()
        val runner = SkillTestRunner(driver = driver, skillBodyReader = reader)
        val states = runner.runOnce("happy-skill", "  ").toList()
        assertEquals(1, states.size)
        val err = states[0] as SkillTestRunner.TestRunState.Error
        assertEquals("empty_prompt", err.error)
    }

    @Test
    fun `harvest with no text and no images emits no_response error`() = runBlocking {
        val driver = FakeDriver(harvestText = "", harvestImages = emptyList())
        val runner = SkillTestRunner(driver = driver, skillBodyReader = reader)
        val states = runner.runOnce("happy-skill", "do something").toList()
        assertEquals(2, states.size)
        val err = states[1] as SkillTestRunner.TestRunState.Error
        assertEquals("no_response", err.error)
    }
}
