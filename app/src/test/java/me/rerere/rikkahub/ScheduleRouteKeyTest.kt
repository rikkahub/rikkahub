package me.rerere.rikkahub

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Route-plumbing regression for SPEC.md M1. The Schedule manager opened from a conversation showed an
 * EMPTY list because [Screen.Schedule] carried only the assistant id — the current conversation id was
 * dropped at the route, so [me.rerere.rikkahub.ui.pages.schedule.ScheduleVM] started unbound and
 * short-circuited [listSchedules] to `emptyList()`. The fix is the route carrying the conversation id;
 * these tests pin that the route key actually transports it (and survives Nav3's serialize round-trip).
 */
class ScheduleRouteKeyTest {

    @Test
    fun schedule_route_carries_conversation_id_through_serialization() {
        val key = Screen.Schedule(assistantId = "a-1", conversationId = "c-9")

        val restored = Json.decodeFromString(
            Screen.Schedule.serializer(),
            Json.encodeToString(Screen.Schedule.serializer(), key),
        )

        assertEquals("a-1", restored.assistantId)
        assertEquals("c-9", restored.conversationId)
    }

    @Test
    fun schedule_route_conversation_id_defaults_to_null_when_unbound() {
        val key = Screen.Schedule(assistantId = "a-1")

        assertNull("an unbound manager (not opened from a conversation) carries no id", key.conversationId)
    }
}
