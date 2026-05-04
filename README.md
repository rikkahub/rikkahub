<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Agent</h1>

A fork of <a href="https://github.com/rikkahub/rikkahub">rikkahub/rikkahub</a> that turns the
native Android LLM chat client into a <strong>fully-capable on-device agent</strong> with deep
Android API access, SSH, persistent scheduled jobs, and a fully-fledged Telegram bot interface
— while preserving Rikka's original design and UX down to the pixel.

<a href="https://github.com/rikkahub/rikkahub">Upstream</a> · <a href="https://github.com/ExTV/rikkahub-agent">This fork</a>
</div>

---

## Why this fork

Upstream RikkaHub is an excellent multi-provider LLM chat client. This fork extends it without
altering any existing UI: every new feature lives behind an opt-in tool toggle in the
per-assistant settings (or a new global settings page that mirrors the existing pattern). If
you turn nothing on, this fork behaves exactly like vanilla RikkaHub.

When you turn things on, you get an agent that can act on your Android device, SSH into remote
machines, run scheduled background tasks, and chat with you over Telegram while you're away
from your phone.

## What's added on top of upstream

### On-device LLM provider — AICore (Gemini Nano)
A first-class provider for Android's AICore (Gemini Nano via ML Kit GenAI). Runs inference
entirely on-device — no API key, no network. Surfaced at the top of Settings → Providers
with two synthetic models (`Gemini Nano FAST` / `Gemini Nano FULL`) and a release-stage
toggle (STABLE / PREVIEW; defaults to PREVIEW since that's what most current beta channels
have feature-gated). Requires Pixel 8/9/10 with the AICore app **plus** enrolment in the
GenAI Prompt-API early-access program (separate opt-in — without it the device returns
ErrorCode 606 / FEATURE_NOT_FOUND).

Tool calling works via prompt engineering: the provider injects a compact
"emit `<tool_call>{...}</tool_call>` markup" protocol into the system prefix, a streaming
parser extracts the tags, and dispatched tools feed `<tool_result>` blocks back into the
next inference round. The same agent tools (Termux, screen automation, telephony, SSH, cron,
Telegram bot — everything in this fork) work on-device too. Cloud providers (OpenAI / Google
/ Claude) keep using their native function-calling APIs — no changes to that path.

Auto-foregrounds RikkaHub before each inference because Google blocks AICore from running
when the calling app is backgrounded. JSON-shape repair on Nano's malformed output (`}>`
to `}}`, primitive `input` strings auto-wrapped under the tool's first required parameter)
keeps the tool dispatch loop robust.

### Permission center
New Settings → Permissions page that auto-discovers every `<uses-permission>` the app
declares plus virtual rows for the AccessibilityService and Notification Listener service
bindings. Each row classifies itself (services / special access / runtime / auto-granted)
and renders a tap-to-grant button wired to the right system flow — runtime perms via
`ActivityResultContracts.RequestPermission`, special-access perms via the matching
`Settings.ACTION_MANAGE_*` deep-link, with auto-refresh on resume so status updates the
moment you return from the system page. Future-proof: new manifest perms appear here
without code changes, and the friendly-label dictionary covers all currently-used
dangerous perms.

### 35 native Android device tools
All implemented natively from the Android SDK — no third-party app dependencies. Each tool is
opt-in per-assistant, behind the same eager-permission flow as upstream's existing tools.

| Section | Tools |
|---|---|
| **Device info** | battery, audio info, telephony info, wifi info, sensors (list + read), storage info |
| **Output** | toast, post notification, share sheet |
| **Hardware control** | torch, vibrate, brightness (get + set), volume (get + set across 6 streams) |
| **Personal data** | location, contacts (search + list), call log, sms inbox (list + search), camera photo, mic recorder, speech-to-text, fingerprint |
| **Media** | media player (play + stop), media scanner, download file, write text file (saves arbitrary content to public Downloads via MediaStore) |

### Screen automation (11 tools)
Full AccessibilityService-backed driver so the LLM can actually operate apps, not just read their
data. One toggle in Settings → Assistant → Local Tools → Screen automation; one separate page at
Settings → Accessibility for service status, recent-actions log, and a diagnostic-screenshot
button.

| Tool | What |
|---|---|
| `tap`, `long_press`, `swipe`, `scroll` | absolute-pixel gestures + scrollable-node-aware scroll |
| `read_window_tree` | filtered active-window tree (visible + interactive nodes), 500-node cap, `verbose:true` for full dump |
| `find_node`, `click_node`, `set_text` | by `text` / `content_description` / `view_id_resource_name`, with `nth` disambiguation; `click_node` walks the parent chain to a clickable ancestor; `set_text` types into editable inputs (URL bars, search, forms — not terminals) |
| `global_action` | system gestures: back, home, recents, notifications, quick_settings, lock_screen, power_dialog |
| `take_screenshot` | `AccessibilityService.takeScreenshot()` (Android 11+), returned as a vision-input image part on the next LLM turn (also lifts cleanly into OpenAI tool-result follow-up so vision models actually receive it) |
| `wake_screen` | turns the display on if it was off; `launch_app` auto-calls this when needed and reports keyguard state |

### App launcher + Termux integration (3 tools)
- `launch_app(package_name)` — opens any installed app; auto-wakes the screen if off; reports
  keyguard state so the LLM knows whether automation can continue.
- `list_installed_apps(filter?, include_no_launcher?)` — discovers package names; sees both
  drawer apps and launcher-less addon packages (e.g. Termux:API) when a filter is given.
- `termux_run_command` — runs shell commands in Termux **with output captured** via Termux's
  `RUN_COMMAND_PENDING_INTENT`. Default mode returns `stdout` / `stderr` / `exit_code` so the
  model can reason on the result; `interactive=true` opens a visible session instead. Settings
  → Assistant → Local Tools → Termux integration row has a live indicator (red / orange /
  yellow / green) and a "tap to verify" smoke test that confirms `allow-external-apps=true` is
  in effect before you trust capture mode.

### Notification awareness (5 tools)
The agent can read your notifications and (optionally) auto-forward whitelisted apps to your
default Telegram chat — so when you're away from the phone, the bot can summarise that WhatsApp
ping or Gmail notification automatically.

- `list_recent_notifications` — 100-entry in-memory ring buffer, filter by package or timestamp.
- `list_active_notifications` — only ones currently in the shade.
- `dismiss_notification(key)` — `cancelNotification(key)` on a live notification.
- `notification_action_click(key, action_index | action_title)` — fire a notification's action
  button (Reply, Mark as Read, etc.); returns `requires_input` for RemoteInput actions so the
  LLM falls back cleanly to screen automation.
- `notification_status` — service bound? whitelist size? default Telegram chat configured?

Settings → Notifications has the live status row, a deep-link to the system notification-access
toggle, the recent-activity log, and an **app picker** with a Switch per app (search, "show only
whitelisted" filter chip, auto-saves on toggle). Empty whitelist by default = nothing forwarded.

### SSH client (8 tools)
- One-shot exec: `ssh_exec` with inline credentials
- Saved hosts (Room-persisted, secrets stored locally): `save_ssh_host`, `list_ssh_hosts`, `delete_ssh_host`, `ssh_exec_saved`
- SFTP: `ssh_upload`, `ssh_download` (saved-host-keyed)
- Strict host-key checking with `accept-new` policy + persistent `known_hosts`
- `ssh_forget_host_key` recovery flow when the user reinstalls a remote (structured error envelope tells the LLM exactly what to do — ask the user, then forget the key, then retry)

### Persistent cron / scheduled jobs (5 tools)
- LLM creates one-shot or recurring prompts via `schedule_job` (one-shot at unix-ms, or interval ≥60s)
- `list_jobs`, `delete_job`, `pause_job`, `resume_job`
- WorkManager-backed; jobs survive app kill and device reboot via a boot receiver
- Each scheduled fire creates a fresh conversation, runs the prompt through the full chat
  pipeline (with all enabled tools), and posts a notification + adds a `[Scheduled]` conversation

### Fully-fledged Telegram bot (14 tools + a settings page)
- Foreground long-poll service routes inbound Telegram messages into the existing chat pipeline
  with full tool access. **Live-streaming reply**: the bot posts a placeholder, then edits it
  in place every ~1.5s with the assistant's accumulating text + a `🔧 Tools used:` summary
  (icon + tool name + concise outcome hint, no JSON dumping). Final edit replaces the
  placeholder; overflow >4000 chars splits cleanly into chunks.
- Per-Telegram-chat conversation memory in Room — multi-turn context preserved across messages.
- **Built-in slash commands handled directly by the app, no LLM round-trip**: `/start`, `/help`,
  `/new`, `/stop`, `/status`, `/model [name]`, `/ratelimit [n|clear]`. Auto-registered with
  Telegram's autocomplete menu on bot start.
- **Per-turn agent_context preamble** injected on every inbound message — tells the LLM what
  model it actually is (so a fresh `/model` switch doesn't get answered with "I'm Claude" by
  whichever model is now active), the chat_id for routing scheduled-job output back, and the
  last few app-side slash commands the user just ran.
- **Inbound photos** are downloaded to the app cache and routed to the LLM as
  `UIMessagePart.Image` parts so vision-capable models can see what you sent.
- **Doze-aware**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission + a one-tap whitelist row in
  the Telegram settings page (because OEM-aggressive ROMs — Xiaomi, OPPO, OnePlus, Vivo, Nothing
  OS — kill background network when the screen turns off otherwise). Plus a partial WakeLock
  around each long-poll cycle so the CPU stays awake during Doze maintenance windows.
- Outbound: `telegram_send_message`, `telegram_send_photo`, `telegram_send_document`.
- Manage Telegram's `/commands` menu: `telegram_set_commands`, `telegram_get_commands`,
  `telegram_delete_commands`.
- Whitelist enforcement (empty whitelist = bot ignores everyone — safe default).
- Direct OkHttp Telegram Bot API client (no third-party bot framework).
- Survives reboot via boot receiver, survives app cold-start via `RikkaHubApp.onCreate`.
- Settings → Telegram bot page for token / whitelist / default chat config + the battery
  optimization whitelist row.
- **Context-aware routing:** the LLM knows when a conversation comes from Telegram and
  automatically schedules cron jobs that deliver back via Telegram, without the user needing
  to say "Telegram" explicitly.

### Default agent skill (auto-installed)
First app launch seeds an `agent-core` skill at the user's Skills folder with three files:

- `SOUL.md` — agent persona / posture (calm, action-oriented, plain-voice, no AI-hedging).
- `HEARTBEAT.md` — periodic awareness loop (when to check device state, recent notifications,
  battery, etc.; how to handle each structured `{error, recovery}` envelope).
- `TOOLS.md` — full reference of every tool surface the LLM has, organised by category, with
  when-to-use hints and gotchas.

Skill files live under the user's filesDir and can be edited in-app via Settings → Skills; user
edits are respected on subsequent launches via a `.seeded` sentinel.

### The killer combo
> "Every weekday at 9am, ssh to my server, check disk usage, telegram me a summary."

The LLM understands and schedules a cron job that, when it fires, executes the SSH command and
delivers the result via Telegram. You don't need to be physically near your phone — the agent
runs autonomously and reports back via your existing Telegram chat.

## All upstream features preserved

Material You design, multi-provider support (OpenAI / Google / Anthropic-compatible), multimodal
input, web access, MCP, Markdown rendering, message branching, search SDKs, prompt variables,
QR import/export, agent customization, ChatGPT-like memory, AI translation, custom HTTP
headers/bodies, Silly Tavern character cards. All untouched.

## Building

```bash
# 1. Clone
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent

# 2. You need a Firebase google-services.json at app/google-services.json. Either provide
#    your own from Firebase console, or stub it out for local debug builds.

# 3. Build & install on a connected device
./gradlew :app:installDebug
```

Tech stack: same as upstream — Kotlin, Jetpack Compose, Koin DI, Room, DataStore, OkHttp/SSE,
Material 3, kotlinx.serialization, kotlinx.coroutines. New deps for this fork: `mwiede/jsch`
(SSH client, maintained Android-friendly fork of JSch), `play-services-location` (Fused
Location), `androidx.biometric` (BiometricPrompt). Coil 3 (already in upstream) is reused for
in-picker app icons.

## Configuring the new features

- **Per-assistant tool toggles** — Settings → Assistants → tap an assistant → Local Tools.
  Sections: Built-in (upstream), Device info, Output, Hardware control, Personal data
  (incl. Notification awareness), Media, Automation (cron jobs), Network (SSH, Telegram bot),
  Screen automation (incl. App launcher + Termux integration with the live verify indicator).

### Setting up the Telegram bot

There are two paths — pick whichever you prefer. Both end at the same state.

**Prerequisites (regardless of path):**
1. Open Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot`, follow the
   prompts. Copy the bot token (looks like `1234567890:ABC-DEF1234ghIkl-zyx57W2v1u123ew11`).
2. Message [@userinfobot](https://t.me/userinfobot), send `/start`. It will reply with your
   numeric Telegram user id (e.g. `708934374`). This is your chat id.
3. In RikkaHub, open any assistant's settings → **Local Tools** → **Network** section → flip
   the **Telegram bot** toggle ON. This exposes the Telegram tools to the LLM for that
   assistant.

#### Path A — let the LLM do it for you

Once the Telegram bot toggle is on for your assistant, just chat with it:

> *Set up the Telegram bot. Token is `<your token>`. My user id is `<your numeric id>`. Set me
> as the default chat. Enable the bot.*

The LLM will call (in order): `telegram_set_token` (verifies the token by calling getMe →
gives you the bot's @username), `telegram_add_whitelist`, `telegram_set_default_chat`,
`telegram_enable` (starts the foreground long-poll service). DM your bot on Telegram and you
should get a reply.

You can ask later: *"check my telegram bot status"* → the LLM calls `telegram_status` and
reports whether the service is alive, who's in the whitelist, the bot's username, etc.

#### Path B — configure manually in the UI

1. Open RikkaHub → **Settings** → **Telegram bot**.
2. Paste your **bot token** (password field; tap the eye to reveal).
3. Set your **default chat** to your numeric Telegram user id.
4. In **Allowed chat IDs**, type your numeric id (or a comma-separated list if you want
   multiple users to be able to talk to your bot).
5. Tap the **Start** FAB at the bottom. The status row should change from "Stopped" to
   "Running".
6. DM your bot on Telegram — it should reply.

Either path persists across app restarts and device reboots; the boot receiver re-launches
the bot service on `BOOT_COMPLETED` if it was enabled.

#### What the LLM can do once the bot is configured

- Reply to your DMs through the bot, with full tool access (battery, location, ssh, file
  writes, anything you've enabled for that assistant).
- Take initiative: when you say *"every weekday at 9am summarize my calendar and dm me on
  telegram"*, the LLM schedules a cron job whose prompt explicitly calls
  `telegram_send_message(chat_id=<you>, text=...)` when it fires. So you get the digest on
  Telegram while you're away from your phone.
- Manage the bot's `/commands` menu directly: ask it to set, get, or clear the slash-command
  hints users see when typing `/` in the chat (`telegram_set_commands`,
  `telegram_get_commands`, `telegram_delete_commands`). The actual behavior of any `/whatever`
  command is just the LLM seeing the message and reacting — the menu is purely cosmetic.

If the bot ever stops responding, ask the LLM *"check the telegram bot"* — `telegram_status`
will tell you whether the service is down (the OS may have killed it), and the LLM will
re-enable it.

## Design principles

The fork is opinionated about preserving Rikka's UX:

- Reuse existing components (`CardGroup`, `FormItem`, `PermissionedSwitch`, `LocalToaster`,
  HugeIcons) — never reinvent.
- Match existing spacing, typography, color, and motion.
- New features become CardGroup sections in existing settings pages, not new top-level
  navigation destinations (with the single exception of Telegram bot, which warrants its own
  page like the existing WebServer page).
- All side-effecting tools that touch shared state (sending messages, modifying remote
  machines, posting to Telegram) require either user approval at call time or explicit
  per-tool toggle gating.

## Status

Shipped: Android device tools (Phases 1+2 — 35 tools), SSH full feature, Telegram bot with
live-streaming replies + slash commands, persistent cron jobs, **screen automation
(AccessibilityService — 11 tools)**, **app launcher + Termux integration with output capture**,
**notification awareness with auto-route to Telegram**, default `agent-core` skill auto-seeded
on first launch.

Still on the roadmap: remaining privileged tools (SMS send, NFC, USB, Wallpaper, Keystore,
Infrared) and on-device inference via Google AI Edge.

## Contributing & license

Same license as upstream. Feature work happens on this fork; bug fixes against upstream RikkaHub
features should go to <a href="https://github.com/rikkahub/rikkahub">rikkahub/rikkahub</a>.

This fork is unaffiliated with the original project maintainers. All credit for the underlying
chat client + provider abstraction + UI design goes to the upstream team.

## Sponsors (upstream)

<div align="center">
  <img src="app/src/main/assets/icons/aihubmix-color.svg" alt="Aihubmix" width="50" />
  <p style="font-size: 14px;">Upstream sponsor — <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> for one-stop access to OpenAI, Claude, Gemini, DeepSeek, Qwen, and hundreds more.</p>
</div>

## License

[License](LICENSE) — inherited from upstream.
</content>
</invoke>