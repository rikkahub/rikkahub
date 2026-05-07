package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRequestValidationTest {

    @Test fun `blank task rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = ""))
        assertEquals("invalid_task", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `whitespace-only task rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "   "))
        assertEquals("invalid_task", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `valid request accepted with trimmed task`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "  go research X  "))
        val ok = r as SubAgentRequestValidator.Result.Ok
        assertEquals("go research X", ok.request.task)
    }

    @Test fun `timeout below 1 rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", timeoutSeconds = 0))
        assertEquals("invalid_timeout", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `timeout above max rejected`() {
        val r = SubAgentRequestValidator.validate(
            SubAgentRequest(task = "x", timeoutSeconds = SubAgentDefaults.MAX_TIMEOUT_SECONDS + 1)
        )
        assertEquals("invalid_timeout", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `max trips below 1 rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", maxTrips = 0))
        assertEquals("invalid_max_trips", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `max trips above 30 rejected`() {
        val r = SubAgentRequestValidator.validate(
            SubAgentRequest(task = "x", maxTrips = SubAgentDefaults.MAX_MAX_TRIPS + 1)
        )
        assertEquals("invalid_max_trips", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `oversize label rejected`() {
        val long = "x".repeat(SubAgentDefaults.MAX_LABEL_LENGTH + 1)
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", label = long))
        assertEquals("invalid_label", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `boundary values accepted`() {
        val ok = SubAgentRequestValidator.validate(
            SubAgentRequest(
                task = "x",
                timeoutSeconds = SubAgentDefaults.MAX_TIMEOUT_SECONDS,
                maxTrips = SubAgentDefaults.MAX_MAX_TRIPS,
                label = "x".repeat(SubAgentDefaults.MAX_LABEL_LENGTH),
            )
        )
        assertTrue(ok is SubAgentRequestValidator.Result.Ok)
    }
}
