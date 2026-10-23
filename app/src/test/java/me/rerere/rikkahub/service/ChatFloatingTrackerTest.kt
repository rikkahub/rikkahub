package me.rerere.rikkahub.service

import me.rerere.rikkahub.service.ChatFloatingTracker.Action
import me.rerere.rikkahub.service.ChatFloatingTracker.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatFloatingTrackerTest {
    private val a = Uuid.random()
    private val b = Uuid.random()

    private fun reduce(vararg actions: Action): TrackingState =
        actions.fold(TrackingState()) { state, action -> state.reduce(action) }

    @Test
    fun `conversation already generating when going to background becomes the target`() {
        val state = reduce(Action.JobsChanged(setOf(a)))
        assertEquals(FloatingChatWindow(a, expanded = false), state.window)
    }

    @Test
    fun `target stays after its generation finishes so the user can reply`() {
        val state = reduce(Action.JobsChanged(setOf(a)), Action.JobsChanged(emptySet()))
        assertEquals(a, state.window?.conversationId)
    }

    @Test
    fun `newly started conversation replaces the target`() {
        val state = reduce(Action.JobsChanged(setOf(a)), Action.JobsChanged(setOf(a, b)))
        assertEquals(b, state.window?.conversationId)
    }

    @Test
    fun `dismissed conversation does not come back until a generation starts`() {
        val dismissed = reduce(Action.JobsChanged(setOf(a, b)), Action.Dismiss)
        assertNull(dismissed.window)
        // 只是别的会话结束了，不算开始新的生成
        assertNull(dismissed.reduce(Action.JobsChanged(setOf(a))).window)
        // 回复推进后重新开始生成
        val restarted = dismissed.reduce(Action.JobsChanged(emptySet())).reduce(Action.JobsChanged(setOf(a)))
        assertEquals(a, restarted.window?.conversationId)
    }

    @Test
    fun `expanding requires a target and dismiss collapses the panel`() {
        assertNull(reduce(Action.Expand).window)
        assertEquals(false, reduce(Action.Expand, Action.JobsChanged(setOf(a))).window?.expanded)

        val expanded = reduce(Action.JobsChanged(setOf(a)), Action.Expand)
        assertEquals(true, expanded.window?.expanded)
        assertEquals(false, expanded.reduce(Action.Collapse).window?.expanded)

        val restarted = expanded.reduce(Action.Dismiss)
            .reduce(Action.JobsChanged(emptySet()))
            .reduce(Action.JobsChanged(setOf(a)))
        assertEquals(false, restarted.window?.expanded)
    }
}
