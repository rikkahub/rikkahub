package me.rerere.rikkahub.data.ai.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [LoopFire] (#364 slice 2): the agent-event payload a CONVERSATION_EVENT schedule
 * enqueues. The shape is the delivery contract (the drain renders [LoopFire.promptOf] as a USER turn),
 * so a round-trip and the malformed-payload guard are pinned here.
 */
class LoopFireTest {

    @Test
    fun `payload round-trips the prompt`() {
        val payload = LoopFire.payloadJson("check the build", Uuid.random())
        assertEquals("check the build", LoopFire.promptOf(payload))
    }

    @Test
    fun `prompt survives JSON-special characters`() {
        val tricky = "line1\nline2 \"quoted\" {brace} \\slash"
        val payload = LoopFire.payloadJson(tricky, Uuid.random())
        assertEquals(tricky, LoopFire.promptOf(payload))
    }

    @Test
    fun `a malformed payload yields null so the drain can fail it instead of injecting empty`() {
        assertNull(LoopFire.promptOf("not json"))
        assertNull(LoopFire.promptOf("{}"))
        assertNull(LoopFire.promptOf("""{"prompt":""}"""))
        assertNull(LoopFire.promptOf("""{"prompt":"   "}"""))
    }

    @Test
    fun `kind is the stable wire constant`() {
        assertEquals("loop.fire", LoopFire.KIND)
    }
}
