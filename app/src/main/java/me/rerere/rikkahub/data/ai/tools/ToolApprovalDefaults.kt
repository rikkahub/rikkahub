package me.rerere.rikkahub.data.ai.tools

/**
 * Single source of truth for which tools require user approval before they execute.
 *
 * The matching policy is "tool name in the set" — there's no per-arg gating yet (e.g. you
 * can't say "approve termux but only if the command doesn't start with rm"). When the
 * runtime decides whether to ask the user, it consults this set, then the per-conversation
 * "Allow for this chat" allow-list, then the persistent "Always Allow" allow-list (in that
 * order); the prompt only fires when none of those let it through.
 *
 * If you add a new LLM-callable tool, decide:
 *   - Is it side-effecting (writes to disk, runs shell, controls hardware, posts to a
 *     remote service, manipulates UI)? → add it here.
 *   - Is it a pure read (battery, location, screenshot, list-installed-apps)? → leave it
 *     out; the user doesn't need to be interrupted for "what's the brightness".
 *
 * Privacy-sensitive READS (contacts, sms, call log) are debatable. Today they're treated as
 * destructive (require approval) because reading PII off the device into an LLM context
 * deserves the same friction as a write does — the secret is leaving the device either way.
 */
object ToolApprovalDefaults {

    /** Tool names that ALWAYS require approval unless the user has granted an exception. */
    val ALWAYS_ASK: Set<String> = setOf(
        // Shell / arbitrary code execution
        "termux_run_command",
        "eval_javascript",

        // Remote shell (SSH)
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
        "ssh_forget_host_key",
        "save_ssh_host",
        "delete_ssh_host",

        // Filesystem / network writes
        "write_text_file",
        "download_file",
        "scan_media",

        // Cron mutations (read is free; mutate asks)
        "schedule_job",
        "delete_job",
        "pause_job",
        "resume_job",

        // UI manipulation through the AccessibilityService
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "set_text",
        "click_node",
        "global_action",
        "launch_app",

        // Privacy / hardware actuation
        "take_photo",
        "record_audio",
        "verify_fingerprint",
        "share",
        "set_torch",
        "vibrate",
        "set_brightness",
        "set_volume",
        "play_media",
        "stop_media",
        "post_notification",

        // Telegram outbound — the bot can DM other chats / change its own config
        "telegram_send_message",
        "telegram_send_photo",
        "telegram_send_document",
        "telegram_set_token",
        "telegram_enable",
        "telegram_disable",
        "telegram_add_whitelist",
        "telegram_remove_whitelist",
        "telegram_set_default_chat",
        "telegram_set_assistant",
        "telegram_set_commands",
        "telegram_delete_commands",
    )

    fun requiresApproval(toolName: String): Boolean = toolName in ALWAYS_ASK
}
