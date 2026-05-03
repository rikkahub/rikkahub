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
- **`get_brightness`** / **`set_brightness`** — 0..255. Requires WRITE_SETTINGS.
- **`get_volume`** / **`set_volume`** — per stream. Requires DND access.

## Media (Phase 1)

- **`play_media`** / **`stop_media`** — play audio from file path or URL.
- **`scan_media`** — tell Android's media scanner about new files so they show up in Gallery / Music.
- **`download_file`** — fetch URL into Downloads via DownloadManager.
- **`write_text_file`** — save arbitrary text to public Downloads.

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

## Termux integration

- **`termux_run_command`** — run a shell command in Termux. **Default mode captures output**: the command runs in the background and `stdout` / `stderr` / `exit_code` come back in the JSON envelope so you can reason on them. Examples: *"is python installed?"* → run `which python3 || echo missing`, read stdout, decide. *"how big is my home dir?"* → `du -sh ~`. Pass `interactive=true` for a visible session that the user can watch (no output capture in that mode — only useful when the user wants to see live output or run an interactive program like `nano`).
  - Setup the user must do once: in Termux run `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`, then force-stop Termux and reopen it. The toggle row in the assistant Local-tools page has a state indicator (red/orange/yellow/green) and a "tap to verify" affordance that runs an end-to-end smoke test — once it goes green capture mode works.
  - Errors return structured envelopes: `termux_not_installed`, `termux_permission_not_granted`, `termux_permission_denied` (allow-external-apps missing), `timeout`. The recovery field tells the user exactly what to fix; surface it verbatim.

## SSH

- **`ssh_exec`** — one-shot remote command. Provide host/port/user/auth or call by saved-host name with `ssh_exec_saved`.
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** — manage saved hosts (Room-persisted).
- **`ssh_upload`** / **`ssh_download`** — SFTP file transfer.
- **`ssh_forget_host_key`** — recovery for "HostKey has been changed" after the user reinstalled a remote. Only call after the user explicitly confirms the remote is theirs.

## Cron / scheduling

- **`schedule_job`** — create one-shot or recurring job. `interval_seconds` minimum 60. The cron prompt should explicitly say *"telegram_send_message(chat_id=…)"* if the result needs to reach a Telegram chat — the worker has no idea where to deliver otherwise.
- **`list_jobs`** / **`pause_job`** / **`resume_job`** / **`delete_job`** — manage existing jobs.

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
