package me.rerere.rikkahub.data.ai.tools

/**
 * Phase 17 stability — context every tool factory in [LocalTools.getTools] sees about WHO
 * is invoking it. Until this layer existed, tools that needed to know the calling
 * conversation / assistant id (sub-agent recursion guard, workflow_create authoring-id
 * persistence) had no way to read it — both shipped with placeholder defaults and silent
 * gaps the audit caught.
 *
 * Convention: every getTools() caller MUST construct a ToolInvocationContext with the most
 * specific data it has. The default ([EMPTY]) is a no-op safe fallback used when the
 * caller doesn't track the data (legacy / one-off paths) — but factories should treat the
 * empty context as "I don't know" not "no constraints", and apply conservative defaults.
 *
 * Fields:
 *  - [callerAssistantId]: the assistant whose toggles are being dispatched. ChatService
 *    knows this from `settings.getCurrentAssistant().id`. Cron / workflow / sub-agent
 *    paths know it from their respective entity's assistant id.
 *  - [callerConversationId]: the conversation-uuid of the user-facing chat (interactive)
 *    or the headless conversation (cron / workflow / sub-agent / external-automation).
 *  - [isHeadless]: true when the dispatch is happening from a system flow rather than the
 *    user typing in a chat. Sub-agents, cron jobs, workflows, and external-automation
 *    runs all set this to true so the recursion guard fires.
 */
data class ToolInvocationContext(
    val callerAssistantId: String? = null,
    val callerConversationId: String? = null,
    val isHeadless: Boolean = false,
) {
    companion object {
        /** No-knowledge fallback. Factories that depend on context MUST handle this. */
        val EMPTY = ToolInvocationContext()
    }
}
