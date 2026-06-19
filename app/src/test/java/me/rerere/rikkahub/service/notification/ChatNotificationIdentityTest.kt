package me.rerere.rikkahub.service.notification

import me.rerere.rikkahub.service.A2aServerService
import me.rerere.rikkahub.service.GenerationForegroundService
import me.rerere.rikkahub.service.WebServerService
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationIdentityTest {

    @Test
    fun `distinct conversations map to distinct live-update notification identities`() {
        val first = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val second = Uuid.parse("22222222-2222-2222-2222-222222222222")

        val firstIdentity = getLiveUpdateNotificationIdentity(first)
        val secondIdentity = getLiveUpdateNotificationIdentity(second)

        assertEquals(2003, firstIdentity.id)
        assertEquals(firstIdentity.id, secondIdentity.id)
        assertNotEquals(firstIdentity.tag, secondIdentity.tag)
        assertNotEquals(firstIdentity, secondIdentity)
    }

    @Test
    fun `same conversation maps to stable live-update notification identity`() {
        val conversationId = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val first = getLiveUpdateNotificationIdentity(conversationId)
        val second = getLiveUpdateNotificationIdentity(conversationId)

        assertEquals(first, second)
        assertEquals(conversationId.toString(), first.tag)
    }

    @Test
    fun `live-update identity does not reuse foreground service notification id`() {
        val conversationId = Uuid.parse("44444444-4444-4444-4444-444444444444")
        val identity = getLiveUpdateNotificationIdentity(conversationId)
        assertTrue(identity.id != GenerationForegroundService.NOTIFICATION_ID)
        assertTrue(identity.tag == conversationId.toString())
    }

    @Test
    fun `every foreground-service notification id is distinct`() {
        // Two foreground services sharing one notification id collide: a stopForeground(REMOVE) from
        // one cannot remove a notification another live FGS still backs, so it lingers with the last
        // content stamped. A2aServerService once shared 2002 with GenerationForegroundService, leaving
        // the server notification stuck reading "Generating response..." after a turn ended. Pin the
        // whole id namespace so any future reuse fails here, not on a device.
        val ids = listOf(
            WebServerService.NOTIFICATION_ID,
            GenerationForegroundService.NOTIFICATION_ID,
            A2aServerService.NOTIFICATION_ID,
            getLiveUpdateNotificationIdentity(
                Uuid.parse("77777777-7777-7777-7777-777777777777")
            ).id,
        )
        assertEquals("foreground notification ids must be unique", ids.size, ids.toSet().size)
        assertNotEquals(
            A2aServerService.NOTIFICATION_ID,
            GenerationForegroundService.NOTIFICATION_ID,
        )
    }

    @Test
    fun `live-update pending intent spec carries conversation id and request code`() {
        val firstConversation = Uuid.parse("55555555-5555-5555-5555-555555555555")
        val secondConversation = Uuid.parse("66666666-6666-6666-6666-666666666666")
        val firstSpec = getLiveUpdateIntentSpec(firstConversation)
        val secondSpec = getLiveUpdateIntentSpec(secondConversation)

        assertEquals(firstConversation.toString(), firstSpec.conversationId)
        assertEquals(firstConversation.toString().hashCode(), firstSpec.requestCode)
        assertEquals(secondConversation.toString(), secondSpec.conversationId)
        assertEquals(secondConversation.toString().hashCode(), secondSpec.requestCode)
        assertNotEquals(firstSpec.action, secondSpec.action)
        assertTrue(firstSpec.action.contains(firstConversation.toString()))
        assertTrue(secondSpec.action.contains(secondConversation.toString()))
    }
}
