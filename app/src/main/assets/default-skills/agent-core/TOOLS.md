# Tools — RikkaHub Agent Reference

Every tool the agent can call, grouped by capability surface. Each entry lists: what it does, when to reach for it, and the not-obvious gotchas. Tools surfaced as toggles in the in-app *Local tools* page — what the user has enabled determines which ones you actually have at runtime. Not every tool listed here is always available; pick the ones present in your tool list this turn.

## Built-in (Phase 0)

- **`eval_javascript`** — run JS inside QuickJS for arithmetic, string transforms, JSON shaping. No Node / DOM.
- **`get_time_info`** — date, weekday, ISO time, timezone, epoch ms. Cheap; call before any scheduling.
- **`clipboard_tool`** — read/write the device clipboard. Don't write unless the user asked.
- **`text_to_speech`** — speak text aloud. Returns immediately; audio plays in background.
- **`ask_user`** — surface a question with optional pre-canned options. Use when proceeding without a clarification would waste work.

## Device info (Phase 1)

- **`get_battery_status`** — percent, charging, plug type, temperature.
- **`get_audio_info`** — current audio mode, headphones connected, ringer mode.
- **`get_telephony_info`** — SIM operator, network type, signal strength. Requires READ_PHONE_STATE.
- **`get_wifi_info`** — current SSID, BSSID, IP, signal. Requires fine location.
- **`list_sensors`** / **`read_sensor`** — enumerate and sample any device sensor.
- **`get_storage_info`** — free / used / total bytes for internal + external storage.

## Output / notify (Phase 1)

- **`show_toast`** — short transient overlay; not stored.
- **`post_notification`** — system notification with optional click intent.
- **`share`** — send a string / file via the system share sheet.

## Hardware control (Phase 1)

- **`set_torch`** — flashlight on/off.
- **`vibrate`** — pattern or duration. One of `pattern` or `duration_ms`, not both.
- **`get_brightness`** / **`set_brightness`** — 1..255 (the tool clamps below 1 because brightness=0 produces no visible change on most Android builds). For "lowest brightness" requests, pass `1`. Requires WRITE_SETTINGS.
- **`get_volume`** / **`set_volume`** — per stream. Requires DND access.

## Media (Phase 1)

- **`play_media`** — START a new playback session from position 0. Replaces any
  existing session (DESTRUCTIVE). Optional `title`/`artist`/`album`/`artwork_uri`
  populate the system media notification.
- **`pause_media`** / **`resume_media`** — pause/resume the active session WITHOUT
  losing position. Use `resume_media` (not `play_media`) to continue playback.
- **`seek_media(position_ms)`** — jump within the active session. Works whether
  playing or paused. Preserves play/pause state.
- **`get_media_status`** — current track / position / duration / play-state.
  Free / no approval needed.
- **`stop_media`** — stop and dismiss the notification.
- **`scan_media`** — tell Android's media scanner about new files so they show up in Gallery / Music.
- **`download_file`** — fetch URL into Downloads via DownloadManager.
- **`write_text_file`** — save text to a path. Defaults refuse if file exists.

**Troubleshooting media:** if the user says "I can't hear anything" while a session
is active, DO NOT call `play_media` — that restarts from 0 and loses the user's
position. Instead: `get_media_status` (is it actually playing?), `get_volume` and
`get_audio_info` (volume / mute state), `set_volume` if needed. Only fall back to
`play_media` if the session is genuinely gone.

## File manager (new)

- **`list_files(path, pattern?, recursive?, limit?)`** — directory listing with optional glob.
- **`find_files(root, query, recursive?, limit?)`** — recursive name-substring search.
- **`read_file(path, max_bytes?, encoding?)`** — text or binary read; auto-detects.
- **`write_text_file(path, content, append?, overwrite?)`** — writes text. Default refuses if file exists. Pass `overwrite=true` to truncate or `append=true` to append.
- **`write_binary_file(path, base64_content, overwrite?)`** — base64 → file.
- **`copy_file(src, dst, overwrite?)`** / **`move_file(src, dst, overwrite?)`** — duplicate / rename.
- **`create_directory(path)`** — mkdir -p semantics.
- **`delete_file(path, recursive?)`** — refuses non-empty dirs without `recursive=true`.
- **`file_info(path, include_hash?)`** — stat with optional sha256.

System paths (`/system`, `/proc`, `/dev`, `/data/data/<other-apps>`) are blocked
unconditionally with a `path_blocked` envelope. Path-traversal via `..` is
canonicalized and blocked too. Prefer these tools over `termux_run_command` for
file operations — faster, no shell needed, no Termux dependency.

## Personal data (Phase 2)

- **`get_location`** — current lat/long. 30s default timeout, falls back to last-known fix with `cached:true` annotation.
- **`search_contacts`** / **`list_contacts`** — read contacts. Requires READ_CONTACTS.
- **`list_call_log`** — recent incoming/outgoing/missed calls.
- **`list_sms_inbox`** / **`search_sms`** — read inbox SMS only. Cannot send (Phase 3 territory).
- **`take_photo`** — opens camera UI; user must take the shot. Returned as image attachment so you can see it.
- **`record_audio`** — fixed-duration mic capture.
- **`speech_to_text`** — short utterance recognition.
- **`verify_fingerprint`** — biometric prompt; succeeds on user thumbprint.

## Screen automation (Phase 4)

Always read the screen *before* gesturing. The right pattern is `read_window_tree` → choose target → `click_node` / `set_text` (or `tap` if you know coordinates).

- **`tap`** — single tap at absolute pixels.
- **`long_press`** — same as tap but with a hold duration (default 600ms, range 100-5000).
- **`swipe`** — start → end with duration (default 300ms, range 50-5000).
- **`scroll`** — direction up/down/left/right; falls back to swipe gesture if no scrollable container is found.
- **`read_window_tree`** — current foreground window. Default mode filters to interactive nodes; pass `verbose:true` for the full tree (large; use sparingly). 500-node default cap.
- **`find_node`** / **`click_node`** — selector by `text` / `content_description` / `view_id_resource_name`. `nth` disambiguates when multiple match. `click_node` walks up the parent chain to find a clickable ancestor automatically.
- **`set_text`** — type into an editable input (URL bar, search field, form input). Locate the field with the same selector axes as `find_node`. **Does not work for terminals** like Termux that render to a Surface — for those, use `termux_run_command`.
- **`global_action`** — system gestures: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen`, `power_dialog`.
- **`take_screenshot`** — captures current display, returned as a vision-input image part on your next turn. Secure surfaces (DRM, banking, password fields) error out gracefully. ~1/sec OS rate limit.
- **`wake_screen`** — turns the display on if it was off. Call this before `launch_app` or any gesture when the device may be asleep. Reports `keyguard_secure:true` if a real PIN is set; in that case the user must unlock manually before automation can continue.

## App launcher

- **`launch_app`** — open any installed app by package name. Auto-wakes the screen if it was off and reports `woke_screen:true`. Use this to bring Termux / Settings / Chrome / any installed app to the foreground before screen automation.
- **`list_installed_apps`** — discover available package names. Filter by substring; defaults to user-installed apps only.
- **`open_url`** — hand a URL to the system's default handler. **Strongly preferred over `launch_app` + screen automation when the user's request maps cleanly to a URL.** Examples:
  - "search hello in chrome" → `open_url("https://www.google.com/search?q=hello")` — done in one tool call. Do NOT try to drive Chrome's URL bar via `set_text`; it is unreliable and you will loop.
  - "open google.com" → `open_url("https://google.com")`
  - "call 555-1234" → `open_url("tel:555-1234")`
  - "show 1600 Amphitheatre Pkwy on a map" → `open_url("geo:0,0?q=1600+Amphitheatre+Pkwy")`
  - "email foo@bar.com" → `open_url("mailto:foo@bar.com")`

  Pass `package_name` to force a specific browser; otherwise the system default opens.

## Termux integration

- **`termux_run_command`** — run a shell command in Termux. **Default mode captures output**: the command runs in the background and `stdout` / `stderr` / `exit_code` come back in the JSON envelope so you can reason on them. Examples: *"is python installed?"* → run `which python3 || echo missing`, read stdout, decide. *"how big is my home dir?"* → `du -sh ~`. Pass `interactive=true` for a visible session that the user can watch (no output capture in that mode — only useful when the user wants to see live output or run an interactive program like `nano`).
  - Setup the user must do once: in Termux run `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`, then force-stop Termux and reopen it. The toggle row in the assistant Local-tools page has a state indicator (red/orange/yellow/green) and a "tap to verify" affordance that runs an end-to-end smoke test — once it goes green capture mode works.
  - Errors return structured envelopes: `termux_not_installed`, `termux_permission_not_granted`, `termux_permission_denied` (allow-external-apps missing), `timeout`. The recovery field tells the user exactly what to fix; surface it verbatim.
  - **Install source:** ONLY recommend the official GitHub releases page at `https://github.com/termux/termux-app/releases`. Do NOT recommend the Play Store or F-Droid — those builds are unmaintained and have known incompatibilities with newer Android versions. Same applies to addons (Termux:API, Termux:Boot, Termux:Styling, Termux:X11): GitHub releases only.
  - **Local HTTP servers:** When you spin up a server in Termux that the user will hit from a browser on the *same phone*, bind it to `0.0.0.0` and visit `http://127.0.0.1:PORT` — never `localhost`. Some Android browsers and ROMs resolve `localhost` only over IPv6 loopback or fail outright; `127.0.0.1` is reliable. Also `pkill -f <process>` before relaunching, since a recently-killed server can leave the port in TIME_WAIT for ~30s and the new bind silently fails.
  - **Noninteractive by default:** `command`-mode invocations are auto-wrapped with `DEBIAN_FRONTEND=noninteractive` and dpkg `--force-confdef --force-confold`, so `pkg upgrade` / `apt install` won't hang on debconf prompts. You don't need to set these yourself.

## Notification awareness

When `notification_listener` is enabled, the bound listener service maintains a 100-entry ring buffer of recent notifications and (optionally) auto-forwards whitelisted packages to the user's default Telegram chat.

- **`list_recent_notifications`** — historical lookup. Filter by `package_name`, `since_unix_ms`, or `limit` (default 50). Returns the ring buffer; entries persist until evicted by the 100-cap or until the process dies. Use this when the user asks "what was that ping a minute ago".
- **`list_active_notifications`** — only the notifications still being shown by their owning apps right now. Use this when you intend to act on something the user can see in the shade (dismiss it, click an action).
- **`dismiss_notification`** — `cancelNotification(key)`. Only works on currently active notifications; ring-buffer keys for already-dismissed notifications return `not_found`.
- **`notification_action_click`** — fire one of a notification's action buttons. Pass `action_index` (0-based) OR `action_title` (case-insensitive). If the action requires text input (e.g. WhatsApp Reply with RemoteInput), returns `requires_input` — fall back to `launch_app` + `set_text` + `click_node` from screen automation.
- **`notification_status`** — service bound, ring buffer size, whitelist size, default Telegram chat configured.

The auto-route forwarder is fire-and-forget — it formats the notification as `🔔 [App] Title: Text` and calls Telegram directly without an LLM round-trip. Empty whitelist by default; the user opts apps in via Settings → Notifications.

## Detecting Termux addons

Termux:API, Termux:Boot, etc. are real installed packages but have **no launcher icon** — they show up only when `list_installed_apps` is called with a `filter` (or `include_no_launcher=true`). Each row carries `has_launcher: bool`; addons return `has_launcher: false` but `package` and `label` are still set, which is enough to confirm presence. The user reporting that `termux-vibrate` or any other `termux-api`-prefixed command works in Termux is conclusive proof that Termux:API is installed even if your earlier `list_installed_apps` call missed it — trust the user.

## SSH

- **`ssh_exec`** — one-shot remote command. Provide host/port/user/auth or call by saved-host name with `ssh_exec_saved`.
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** — manage saved hosts (Room-persisted).
- **`ssh_upload`** / **`ssh_download`** — SFTP file transfer.
- **`ssh_forget_host_key`** — recovery for "HostKey has been changed" after the user reinstalled a remote. Only call after the user explicitly confirms the remote is theirs.

## Cron / scheduled jobs (Phase 5)

**Two modes, two timing types:**

- `mode='llm'` — at fire time, your `prompt` is sent to a fresh headless conversation; the model decides what tools to call. Use this when reasoning is required ("if battery < 20%, message me", "summarize last hour of notifications").
- `mode='direct'` — at fire time, the listed `actions[]` execute deterministically without the LLM. Free, fast, predictable. Use this for fixed side effects ("post 'good morning' every 8am").

**Timing:**

- `schedule_type='once'` — fires once at `at_unix_ms`, then auto-disables.
- `schedule_type='cron'` — 5-field cron expression with aliases. Examples:
  - `0 9 * * MON-FRI` — weekdays 9am
  - `*/15 * * * *` — every 15 minutes
  - `@every 30m` — every 30 minutes
  - `@daily` — midnight every day
  - `0 0 1 * *` — first of every month

  Timezone defaults to the device's; pass IANA id via `timezone` to override.

**Bounds (cron only):** `start_at_unix_ms`, `end_at_unix_ms`, `max_runs`.

**Catchup** (default `fire_once`): `skip` / `fire_once` / `fire_all`. Controls what happens for windows missed during reboot.

**Tools:**

- `schedule_job`, `list_jobs`, `delete_job`, `pause_job`, `resume_job`
- `trigger_job_now(id)` — fire immediately, doesn't disturb the schedule
- `get_job_history(id, limit?)` — last N runs newest-first, with outcomes

## Telegram bot (LLM-side)

- **`telegram_set_token`** / **`telegram_status`** / **`telegram_enable`** / **`telegram_disable`** — bot lifecycle.
- **`telegram_add_whitelist`** / **`telegram_remove_whitelist`** — restrict who the bot replies to.
- **`telegram_set_default_chat`** / **`telegram_set_assistant`** — defaults for proactive sends.
- **`telegram_send_message`** / **`telegram_send_photo`** / **`telegram_send_document`** — outbound to a specific chat_id.
- **`telegram_set_commands`** / **`telegram_get_commands`** / **`telegram_delete_commands`** — control the `/`-prefix menu Telegram users see when typing.

## Universal envelope shapes

Tools return structured JSON. Common shapes:

- `{success: true, ...}` — happy path.
- `{success: false, reason: "..."}` — operation completed but the result is "no".
- `{error: "...", recovery: "..."}` — broken state, with a hint to surface to the user.

When you see `recovery`, paste it into your reply verbatim — it's written for the user, not for you.
