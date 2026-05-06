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
 * MCP-relayed tools (any name starting with `mcp__`) are gated separately at the
 * GenerationHandler level — this set covers only locally-defined tools. See
 * [requiresApproval] for the combined logic.
 *
 * If you add a new LLM-callable tool, decide:
 *   - Is it side-effecting (writes to disk, runs shell, controls hardware, posts to a
 *     remote service, manipulates UI)? → add it here.
 *   - Is it a pure read (battery, location, screenshot, list-installed-apps)? → leave it
 *     out; the user doesn't need to be interrupted for "what's the brightness".
 *
 * Privacy-sensitive READS (contacts, sms, call log) ARE in here: reading PII off the
 * device into an LLM context deserves the same friction as a write does — the secret
 * is leaving the device either way.
 */
object ToolApprovalDefaults {

    /** Tool names that ALWAYS require approval unless the user has granted an exception. */
    val ALWAYS_ASK: Set<String> = setOf(
        // Shell / arbitrary code execution
        "termux_run_command",
        "transcribe_audio_file",  // shells out to whisper-cli via Termux; reads arbitrary audio files
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
        "trigger_job_now",
        "get_job_history",

        // UI manipulation through the AccessibilityService
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "set_text",
        "click_node",
        "global_action",
        "launch_app",
        "open_url",          // can dial tel:, draft mailto:, hand a URI to any app
        "wake_screen",       // acquires wake lock, turns screen on at night

        // Privacy / hardware actuation
        "take_photo",
        "record_audio",
        "speech_to_text",    // activates the microphone + uploads audio to recognizer
        "verify_fingerprint",
        "share",
        "set_torch",
        "vibrate",
        "set_brightness",
        "set_volume",
        "play_media",
        "stop_media",
        "pause_media",
        "resume_media",
        "seek_media",
        // get_media_status is read-only — no approval needed
        "post_notification",

        // Privacy reads — PII leaves the device into the model's context
        "list_call_log",
        "list_contacts",
        "search_contacts",
        "list_sms_inbox",
        "search_sms",

        // Notification listener side effects (read-only listing is free, mutating is not)
        "dismiss_notification",
        "notification_action_click",

        // File manager — all ops are side-effecting or read PII from arbitrary paths
        "list_files",
        "read_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "file_info",
        "find_files",

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

    /**
     * True if [toolName] requires approval. Local tools are looked up in [ALWAYS_ASK];
     * MCP-relayed tools (`mcp__*`) are always gated because the MCP server's tool surface
     * is opaque to us — we can't know which calls are destructive. An MCP server that
     * exposes purely-read tools costs the user one approval per session via "Always
     * Allow", which is a fair trade for the floor.
     */
    fun requiresApproval(toolName: String): Boolean =
        toolName in ALWAYS_ASK || toolName.startsWith("mcp__")
}
