package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunTimelineFollowTest {
    @Test
    fun childAndTimelineActivityKeysUseIndependentNamespaces() {
        assertEquals(
            listOf("child:shared-id", "timeline:shared-id"),
            agentRunActivityKeys(
                childRunIds = listOf("shared-id"),
                timelineKeys = listOf("shared-id"),
            ),
        )
    }

    @Test
    fun unseenCountMotionFollowsIncomingActivityDirection() {
        assertEquals(AgentRunCountMotion.INCREASE, agentRunCountMotion(initialCount = 1, targetCount = 3))
        assertEquals(AgentRunCountMotion.DECREASE, agentRunCountMotion(initialCount = 3, targetCount = 1))
        assertEquals(AgentRunCountMotion.STEADY, agentRunCountMotion(initialCount = 2, targetCount = 2))
    }

    @Test
    fun followModeScrollsOnlyForNewStableKeys() {
        val update = agentTimelineUpdate(
            previousKeys = setOf("step:1", "tool:1"),
            currentKeys = listOf("step:1", "tool:1", "step:2"),
            currentUnseenCount = 3,
            followingLatest = true,
        )

        assertEquals(setOf("step:1", "tool:1", "step:2"), update.currentKeys)
        assertEquals(1, update.addedCount)
        assertEquals(0, update.unseenCount)
        assertTrue(update.shouldScrollToLatest)

        val statusOnlyUpdate = agentTimelineUpdate(
            previousKeys = update.currentKeys,
            currentKeys = update.currentKeys.toList(),
            currentUnseenCount = update.unseenCount,
            followingLatest = true,
        )

        assertEquals(0, statusOnlyUpdate.addedCount)
        assertFalse(statusOnlyUpdate.shouldScrollToLatest)
    }

    @Test
    fun pausedModeAccumulatesOnlyNewItems() {
        val firstUpdate = agentTimelineUpdate(
            previousKeys = setOf("step:1"),
            currentKeys = listOf("step:1", "tool:1", "step:2"),
            currentUnseenCount = 2,
            followingLatest = false,
        )
        val repeatedUpdate = agentTimelineUpdate(
            previousKeys = firstUpdate.currentKeys,
            currentKeys = firstUpdate.currentKeys.toList(),
            currentUnseenCount = firstUpdate.unseenCount,
            followingLatest = false,
        )

        assertEquals(2, firstUpdate.addedCount)
        assertEquals(4, firstUpdate.unseenCount)
        assertFalse(firstUpdate.shouldScrollToLatest)
        assertEquals(4, repeatedUpdate.unseenCount)
    }

    @Test
    fun pausedModeCountsNewChildAndTimelineAsTwoActivities() {
        val previousKeys = agentRunActivityKeys(
            childRunIds = listOf("child-1"),
            timelineKeys = listOf("timeline-1"),
        ).toSet()
        val currentKeys = agentRunActivityKeys(
            childRunIds = listOf("child-1", "child-2"),
            timelineKeys = listOf("timeline-1", "timeline-2"),
        )
        val update = agentTimelineUpdate(
            previousKeys = previousKeys,
            currentKeys = currentKeys,
            currentUnseenCount = 0,
            followingLatest = false,
        )
        val repeatedUpdate = agentTimelineUpdate(
            previousKeys = update.currentKeys,
            currentKeys = currentKeys,
            currentUnseenCount = update.unseenCount,
            followingLatest = false,
        )

        assertEquals(2, update.addedCount)
        assertEquals(2, update.unseenCount)
        assertEquals(0, repeatedUpdate.addedCount)
        assertEquals(2, repeatedUpdate.unseenCount)
    }

    @Test
    fun detailLastIndexIncludesOptionalSectionsAndEmptyTimeline() {
        assertEquals(5, agentRunDetailLastItemIndex(childCount = 0, timelineCount = 0))
        assertEquals(6, agentRunDetailLastItemIndex(childCount = 0, timelineCount = 2))
        assertEquals(9, agentRunDetailLastItemIndex(childCount = 2, timelineCount = 2))
    }
}
