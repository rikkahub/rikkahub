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
        "open_file",         // ACTION_VIEW on a user-supplied path — same surface as launch_app
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

        // MCP control — all side-effecting MCP tools require approval.
        // mcp_add and mcp_update are also flagged with "no always-allow" below because a
        // hostile MCP server can exfiltrate everything the LLM has access to and we want
        // a per-call confirmation each time, not a one-shot blanket grant.
        "mcp_add",
        "mcp_update",
        "mcp_delete",
        "mcp_set_enabled",
        "mcp_set_tool_approval",

        // External Automation Intent API — flipping any of these changes who can fire
        // the assistant from outside the app. Privilege-escalation surface, requires
        // approval. Reads (`external_automation_status`) are free.
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",

        // Reliability bundle — generate_bug_report writes a file to disk that includes
        // a redacted logcat dump. The redactor catches the common token shapes; we still
        // gate so the user can review what's being created and decide whether to share.
        // check_app_updates is read-only and has no entry here.
        "generate_bug_report",

        // Sub-agents — subagent_dispatch spawns an autonomous LLM run with the parent's
        // tool surface. Approval-required so the user sees the task + tools before
        // delegation happens. list / get / cancel are read-only or user-controlling and
        // have no entry here.
        "subagent_dispatch",

        // Workflows (Phase 12) — every mutator goes through the existing approval flow
        // with a human-readable summary rendered by WorkflowApprovalRenderer. workflow_run
        // fires immediately on approve, with HARDLINE still applied to every action.
        // workflow_list / workflow_get are read-only and have no entry here.
        "workflow_create",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",

        // Skill import (Phase 16) — pulls a markdown / JSON skill from an arbitrary URL
        // (or from raw text the LLM already fetched via ssh / curl / MCP) and installs
        // it. Whatever is in the skill rides along with the assistant's tool surface,
        // so this is privilege-escalation-adjacent. NO_ALWAYS_ALLOW below.
        "skill_install_from_url",
        "skill_install_from_text",

        // JS skills (Phase 18) — run a skill's JavaScript inside a hidden WebView.
        // The script can issue arbitrary network requests on behalf of the user, so
        // every invocation gets per-call approval. Eligible for "Always allow" once a
        // particular skill is trusted (NOT in NO_ALWAYS_ALLOW — the skill's body has
        // already been reviewed at install time).
        "run_js",

        // Native intent tools (Phase 18) — open the system Calendar / Contacts / SMS /
        // Email composer / WiFi settings / Maps app. Each fires a system intent the user
        // finalises in the destination app. Approval gate ensures the user reviews the
        // pre-filled fields before the LLM hands the action off.
        "create_calendar_event",
        "create_contact",
        "send_email_intent",
        "send_sms_intent",
        "open_wifi_settings",
        "show_location_on_map",

        // In-app browser write tools (Phase 21 / Pass 2). The browser can carry auth tokens
        // in cookies, so anything that mutates page state OR runs JS is approval-gated. Read
        // tools (open, current_url, screenshot, get_text, get_dom, get_links, back, forward,
        // wait_for) are NOT in this set — reading text out of a page is the same trust level
        // as taking a screenshot or reading any other LLM context. browser_done is the
        // loop-control sentinel and never side-effects.
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_submit",
        "browser_select",
        "browser_press_key",
        "browser_eval_js",
    )

    /**
     * Tools whose approval prompt MUST drop the "Always Allow" button so the user has to
     * confirm every single call. Reserved for tools that are inherently privilege-escalation
     * surfaces — adding an MCP server is exactly that, since a hostile server can exfiltrate
     * anything reachable through the assistant's tool set. Both in-app and Telegram surfaces
     * read this set when rendering the keyboard.
     */
    val NO_ALWAYS_ALLOW: Set<String> = setOf(
        "mcp_add",
        "mcp_update",
        // Phase 16 — skill_install_from_url + skill_install_from_text. The skill body is
        // fetched from an arbitrary URL (or supplied as raw text via any other tool, e.g.
        // ssh_exec / termux_run_command for authenticated servers) and installed against
        // the assistant's tool surface. We require per-call approval every single time so
        // the user reviews the URL / source-label + skill name.
        "skill_install_from_url",
        "skill_install_from_text",
        // Phase 21 / Pass 2 — browser_eval_js runs arbitrary JavaScript in a real WebView
        // with the user's cookies, localStorage, and authenticated fetch surface. Even
        // after HARDLINE filters out shell-shaped strings + obvious dynamic-eval patterns,
        // the residual surface is too broad to ever blanket-allow. Every invocation gets
        // an explicit per-call approval card, no exceptions.
        "browser_eval_js",
    )

    fun allowsAlwaysAllow(toolName: String): Boolean = toolName !in NO_ALWAYS_ALLOW

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
