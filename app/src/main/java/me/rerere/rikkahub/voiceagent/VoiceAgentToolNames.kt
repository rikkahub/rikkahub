package me.rerere.rikkahub.voiceagent

internal object VoiceAgentToolNames {
    const val ASK_HERMES = "ask_hermes"
    const val ASK_HERMES_DESCRIPTION =
        "Ask Hermes for any substantive user question requiring facts, state, memory, project context, code context, status, decisions, debugging, plans, access, authorization, or external knowledge. Use this instead of answering from Gemini general knowledge. Multiple ask_hermes calls may be pending in parallel."
    const val ASK_HERMES_BEHAVIOR_NON_BLOCKING = "NON_BLOCKING"
    const val ASK_HERMES_RESPONSE_SCHEDULING_WHEN_IDLE = "WHEN_IDLE"
    const val ASK_HERMES_RESPONSE_SCHEDULING_INTERRUPT = "INTERRUPT"
}
