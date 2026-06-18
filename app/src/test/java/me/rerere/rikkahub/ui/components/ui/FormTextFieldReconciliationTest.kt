package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormTextFieldReconciliationTest {

    @Test
    fun external_matching_local_acknowledges_the_edit() {
        // The external source echoed our edit back: clear pending-ack, never rewrite the editor.
        val result = reconcileFormTextField(
            localText = "abcd",
            incomingExternalValue = "abcd",
            awaitingAck = true,
            focused = false,
        )

        assertEquals(FormTextFieldReconciliation.Ack, result)
    }

    @Test
    fun stale_echo_while_focused_does_not_reset_local_text() {
        val result = reconcileFormTextField(
            localText = "abcd",
            incomingExternalValue = "abc",
            awaitingAck = true,
            focused = true,
        )

        assertEquals(FormTextFieldReconciliation.KeepLocal, result)
    }

    @Test
    fun stale_echo_after_blur_before_ack_does_not_clobber_typed_text() {
        // The critical no-jump property: user typed "abcd" and blurred before the echo landed; a
        // stale in-flight external value must NOT be written into the buffer.
        val result = reconcileFormTextField(
            localText = "abcd",
            incomingExternalValue = "ab",
            awaitingAck = true,
            focused = false,
        )

        assertEquals(FormTextFieldReconciliation.KeepLocal, result)
    }

    @Test
    fun genuine_external_change_while_idle_and_in_sync_is_adopted() {
        // Field is in sync (not awaiting ack) and unfocused; a differing external value is a real
        // change (e.g. a reset-to-default button) and is adopted.
        val result = reconcileFormTextField(
            localText = "abc",
            incomingExternalValue = "reset",
            awaitingAck = false,
            focused = false,
        )

        assertEquals(FormTextFieldReconciliation.AdoptExternal, result)
    }

    @Test
    fun external_change_while_focused_in_sync_is_left_alone() {
        val result = reconcileFormTextField(
            localText = "abc",
            incomingExternalValue = "elsewhere",
            awaitingAck = false,
            focused = true,
        )

        assertEquals(FormTextFieldReconciliation.KeepLocal, result)
    }

    @Test
    fun normalized_echo_keeps_local_text_no_clobber_tradeoff() {
        // Deliberate tradeoff: a value-only reconciler cannot tell a stale in-flight echo from a
        // normalized echo (e.g. the VM trimmed "abc " to "abc"). While awaiting the ack we KEEP the
        // local text rather than risk clobbering freshly typed text — no data loss, no cursor jump.
        // The normalized value is reflected on the next entity/row key change (reseed). Fields whose
        // value the VM normalizes (trim/parse) have no same-key external source, so this never strands
        // a real external update in practice.
        val result = reconcileFormTextField(
            localText = "abc ",
            incomingExternalValue = "abc",
            awaitingAck = true,
            focused = false,
        )

        assertEquals(FormTextFieldReconciliation.KeepLocal, result)
    }

    @Test
    fun external_key_change_resets_buffer_immediately() {
        val oldBuffer = FormTextFieldBufferSnapshot(
            externalKey = "assistant-a:name",
            localText = "dirty local",
            syncedExternalValue = "old external",
        )

        val nextBuffer = resetFormTextFieldBufferOnKeyChange(
            previous = oldBuffer,
            externalKey = "assistant-b:name",
            value = "new assistant",
        )

        assertEquals("assistant-b:name", nextBuffer.externalKey)
        assertEquals("new assistant", nextBuffer.localText)
        assertEquals("new assistant", nextBuffer.syncedExternalValue)
        assertTrue(nextBuffer.didReset)
    }

    @Test
    fun unchanged_external_key_keeps_existing_buffer() {
        val oldBuffer = FormTextFieldBufferSnapshot(
            externalKey = "assistant-a:name",
            localText = "dirty local",
            syncedExternalValue = "old external",
        )

        val nextBuffer = resetFormTextFieldBufferOnKeyChange(
            previous = oldBuffer,
            externalKey = "assistant-a:name",
            value = "new external",
        )

        assertEquals(oldBuffer.externalKey, nextBuffer.externalKey)
        assertEquals(oldBuffer.localText, nextBuffer.localText)
        assertEquals(oldBuffer.syncedExternalValue, nextBuffer.syncedExternalValue)
        assertFalse(nextBuffer.didReset)
    }
}
