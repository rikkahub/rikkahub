package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard policy for the unified submit path (review finding on bd687d96): the
 * no-model-selected toast is only correct when the submit will actually request a model
 * answer. ChatService.sendMessage starts generation solely when `answer` is true, so the
 * long-press "send without answer" path must keep working with no chat model configured.
 */
class ChatPageSubmitGuardTest {

    @Test
    fun `tap send without a chat model is blocked`() {
        assertTrue(shouldBlockSubmitForMissingModel(answer = true, hasChatModel = false))
    }

    @Test
    fun `long-press send without a chat model is NOT blocked - it only records the message`() {
        assertFalse(shouldBlockSubmitForMissingModel(answer = false, hasChatModel = false))
    }

    @Test
    fun `submits with a chat model configured are never blocked`() {
        assertFalse(shouldBlockSubmitForMissingModel(answer = true, hasChatModel = true))
        assertFalse(shouldBlockSubmitForMissingModel(answer = false, hasChatModel = true))
    }
}
