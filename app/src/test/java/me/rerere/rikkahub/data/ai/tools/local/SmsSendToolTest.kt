package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 25 — `send_sms` validation. Exercises [validateSmsArgs] directly plus the tool's
 * early-return validation path (which never reaches a Context method).
 */
class SmsSendToolTest {

    @Test fun `validate rejects blank recipient`() {
        assertEquals("recipient is required", validateSmsArgs("", "hi"))
        assertEquals("recipient is required", validateSmsArgs(null, "hi"))
    }

    @Test fun `validate rejects non-numeric recipient`() {
        assertEquals("recipient must be a phone number", validateSmsArgs("abc-xyz", "hi"))
        assertEquals("recipient must be a phone number", validateSmsArgs("+1 (555) hello", "hi"))
    }

    @Test fun `validate accepts well-formed numbers`() {
        assertNull(validateSmsArgs("+1 (555) 123-4567", "hi"))
        assertNull(validateSmsArgs("5551234567", "hi"))
    }

    @Test fun `validate rejects empty body`() {
        assertEquals("body is required", validateSmsArgs("5551234567", ""))
        assertEquals("body is required", validateSmsArgs("5551234567", null))
    }

    @Test fun `validate rejects oversized body`() {
        val tooLong = "a".repeat(4097)
        assertEquals("body must be <= 4096 characters", validateSmsArgs("5551234567", tooLong))
    }

    @Test fun `validate accepts max-length body`() {
        assertNull(validateSmsArgs("5551234567", "a".repeat(4096)))
    }

    @Test fun `tool early-returns validation error for bad recipient`() {
        val tool = smsSendTool(NULL_CONTEXT)
        val out = execTool(tool, """{"recipient":"!!!","body":"hi"}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("recipient must be a phone number", obj["error"]?.jsonPrimitive?.content)
    }

    @Test fun `tool early-returns validation error for missing body`() {
        val tool = smsSendTool(NULL_CONTEXT)
        val out = execTool(tool, """{"recipient":"5551234567"}""")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("body is required", obj["error"]?.jsonPrimitive?.content)
    }
}
