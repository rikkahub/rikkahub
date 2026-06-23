package me.rerere.rikkahub.service.notification

import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * The chat notification operations ChatService drives (#360 P1a). Extracted as a narrow port so
 * ChatService depends on the THREE notification use-cases it calls — not on the concrete
 * [ChatNotificationSender], which holds an Android `Context` and talks to the system NotificationManager
 * (untestable on the JVM unit path). A test supplies a recording/no-op fake; production binds the sender.
 *
 * The port exposes use-case operations only (NO Android types cross it) so the seam stays Android-free.
 */
interface ChatNotifications {
    /** Post/refresh the "generation in progress" Live Update notification for [conversationId]. */
    fun sendLiveUpdate(conversationId: Uuid, senderName: String, parts: List<UIMessagePart>)

    /** Cancel the Live Update notification for [conversationId] (turn ended). */
    fun cancelLiveUpdate(conversationId: Uuid)

    /** Post the "generation done" notification for [conversationId] (only when app is backgrounded). */
    fun sendGenerationDone(conversationId: Uuid, senderName: String, contentPreview: String)
}
