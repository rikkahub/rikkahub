<div align="center">
  <img src="docs/icon.png" alt="RikkaHub Agent" width="120" />

  <h1>RikkaHub Agent</h1>

  <p><strong>Your Android phone, but it can actually <em>do</em> things.</strong></p>

  <p>
    A fork of <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a> that turns the
    beautiful native Android LLM chat client into a real on-device agent —
    with optional Telegram remote control, scheduled jobs, SSH, screen automation,
    deep device access, and a three-layer safety model (per-tool toggles,
    per-call approval prompts, hardline floor). Pixel-perfect with the original UX.
  </p>

  <p>
    <a href="https://github.com/rikkahub/rikkahub">Upstream</a> ·
    <a href="https://github.com/ExTV/rikkahub-agent">This fork</a> ·
    <a href="https://github.com/ExTV/rikkahub-agent/releases">Releases</a> ·
    <a href="#getting-started">Getting started</a>
  </p>
</div>

---

## The pitch

Imagine telling your phone:

> *"Every weekday at 9am, check disk space on my server and message me on Telegram."*

And it just... does it. While you're at lunch. Without you opening the app.

That's what this fork adds. The LLM you already chat with on your phone can now
act on your device, talk to you over Telegram while you're away, run scheduled
jobs in the background, and reach out to remote servers — all opt-in, all
behind toggles you control.

Turn nothing on, and it behaves exactly like vanilla RikkaHub. Flip switches,
and it grows into an agent.

## What can it do

### Talk to it from anywhere — through Telegram

Set up a private Telegram bot in under a minute. From then on, you can chat with
your AI assistant from any device, anywhere — and the assistant has full access
to whatever tools you've enabled on your phone. It streams replies live as it
thinks, summarizes which tools it used, posts a per-turn token-usage footer,
and remembers your conversation across messages. Markdown bold / italic /
code blocks render natively in Telegram. Built-in slash commands (`/new`,
`/stop`, `/status`, `/model`, `/help`, `/ratelimit`) handle in single-digit
milliseconds with no LLM round-trip. `/model` with no arg pops an interactive
keyboard listing every configured model — tap to switch.

### Approve what the agent does, only when it matters

Side-effecting tools (shell, SSH, file writes, screen automation, Telegram
outbound, notification action clicks, MCP server calls) prompt for approval
before running. Each prompt offers four buttons: **Allow** for one call,
**Allow for this chat** for the current conversation, **Always Allow** to
persist across sessions, and **Deny**. Same UX in-app and over Telegram.

A **HARDLINE floor** sits below approval and blocks unconditionally: `rm -rf /`
and system dirs (descendant paths included), `mkfs.*`, `dd of=/dev/sda`, fork
bombs, `kill -1`, `shutdown` / `reboot` / `halt` / `poweroff` / `init 0|6` /
`systemctl poweroff`, raw block-device redirects, and shell-eval bypass forms
like `bash -c "shutdown"`, `base64 -d | sh`, `printf '\xNN' | sh`, `eval $(…)`.
HARDLINE catches these in `termux_run_command`, `ssh_exec`, `ssh_exec_saved`,
and any `mcp__*` tool — even if you've granted Always Allow on the parent tool.

If you trust the agent fully, **Settings → Tool approvals → "I AM STUPID"**
flips a global auto-approve switch (behind a confirm dialog) so nothing prompts
ever again. The HARDLINE floor still applies. The page background goes red so
you can't forget you're in YOLO mode.

### Run jobs while you sleep

Schedule one-off or recurring jobs. *"Every Monday morning, summarize my
unread notifications and DM me."* The agent stores these persistently — they
survive reboots, app kills, and battery saver — and reports back through your
Telegram chat, an Android notification, or a fixed action list, depending on
how the prompt is phrased.

Two modes:

- **`mode='llm'`** — at fire time the prompt is sent to a fresh headless
  conversation; the model decides what tools to call. Pick this when reasoning
  is required (*"if battery is below 20%, message me"*).
- **`mode='direct'`** — at fire time, a fixed list of tool calls runs
  deterministically without invoking the LLM at all. Free, fast, predictable.
  Pick this for known side effects (*"post 'good morning' every 8am"*,
  *"screenshot every hour"*).

Two timing types:

- **`once`** — fires at a single absolute timestamp, then auto-disables.
- **`cron`** — full 5-field UNIX cron expressions with ranges (`1-5`),
  lists (`1,3,5`), steps (`*/15`), and aliases (`@hourly`, `@daily`, `@weekly`,
  `@monthly`, `@yearly`, `@every 30m`, `@every 2h`, `@every 1d`).

Plus per-job options: IANA `timezone`, `start_at_unix_ms` for delayed start,
`end_at_unix_ms` for bounded windows, `max_runs` to auto-disable after N
successful fires (counted from history, not a cached counter — replay-safe),
and `catchup` policy (`skip` / `fire_once` default / `fire_all` capped at 20)
for fires missed during reboot.

Two extra LLM tools beyond the basics: **`trigger_job_now(id)`** fires a job
immediately without disturbing the schedule, and **`get_job_history(id, limit)`**
returns the last N runs with outcomes (`success`/`failed`/`timed_out`/
`process_killed_replay`/`skipped_catchup`/`concurrent_skip`), timestamps, and
the headless `conversation_id` for `llm`-mode runs.

Scheduled jobs run in **headless** mode: tools auto-approve at fire time
(you pre-authorised the schedule itself, with a clear warning that lists the
exact actions, when the model called `schedule_job`). HARDLINE still applies
inside cron — `rm -rf /` smuggled into a direct-mode action gets refused at
both job-create time AND every fire.

### Operate your phone like you would

With a single permission grant, the agent can tap, swipe, scroll, type, take
screenshots, and read what's on screen. Vision-capable models can actually see
the screen and decide what to do next. Older screenshots in long automation
turns are auto-elided from the model's context to keep token usage sane on
multi-step flows.

### Reach into your device

Need the agent to check battery, read your location, send a notification, flick
the torch on, adjust volume, scan WiFi, list contacts, or save a file?
There's a tool for that — 47+ of them, all native Android, no third-party
apps required. Each is opt-in per assistant.

### SSH from your pocket

Saved hosts, one-shot commands, file upload/download, proper host-key checking,
parallel network probing for fast connect across WiFi + cellular. Stdout /
stderr cap at 8 KB / 2 KB to keep huge log dumps from blowing the model's
context. Ask the assistant to log into your server and tail a log — it does.

### Live notification awareness

The agent can read incoming notifications and (optionally) auto-forward
whitelisted apps to Telegram. WhatsApp pings, emails, calendar reminders —
relayed and summarized while you're away from your phone. Dedup is recorded
only after a successful Telegram send, so a transient network failure doesn't
permanently mute that thread.

### Termux integration

Run shell commands inside Termux *with output captured*, so the model can
actually reason about what came back.

### On-device inference (Pixel 8/9/10)

First-class support for Gemini Nano via Android's AICore — runs entirely
on-device, no API key, no network. Tool calling works on-device too. The
provider ships **off by default**; AICore-eligible users flip a single toggle
on the provider card to enable it.

### Privacy + integrity guards

- Tool-call args containing `password`, `private_key`, `passphrase`, `token`,
  `api_key` etc. are redacted in logcat — raw secrets never land in
  `adb logcat` or in `bugreport` dumps.
- Backup rules exclude `databases/`, `sharedpref/`, `datastore/`, and
  `known_hosts` — SSH credentials and the Telegram bot token don't get walked
  off the device by Android cloud backup.
- Telegram bot agent-context (model name, chat id) lives in the system prompt,
  not the user message — older turns no longer replay ~80 tokens × N turns of
  duplicate framing.
- Tool execution is **idempotent across process kills**: if a tool started
  but didn't finish (process died mid-execute), the post-restart replay shows
  it as `Denied("interrupted_unknown_outcome")` instead of silently re-running.
  No double-charged remotes, no double-sent messages.

### A permission center that gets out of your way

A dedicated Settings page lists every permission the app uses, classifies it,
and gives you a one-tap button to grant it through the right system flow.

## Everything from upstream, untouched

Material You design, multi-provider support (OpenAI / Google / Anthropic /
compatible APIs), multimodal input, web access, MCP, Markdown rendering,
message branching, search, prompt variables, QR import/export, agent
customization, ChatGPT-style memory, AI translation, Silly Tavern character
cards. All preserved exactly as upstream.

## Getting started

A brand-new install does *nothing extra* by default — it behaves exactly like
vanilla RikkaHub until you opt in. Here's the order that actually works.

### 1. Install the app

**Recommended — download the APK** from the
[Releases page](https://github.com/ExTV/rikkahub-agent/releases). Grab the
latest `rikkahub-agent-*-universal-debug.apk`, allow install from unknown
sources when prompted, and open it.

> **If you have an older agent-fork build installed, uninstall it first** —
> the signing keys may differ between releases.

**Or build from source:**

```bash
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent
./gradlew :app:installDebug
```

You'll need a `google-services.json` at `app/google-services.json` — either
your own from the Firebase console, or stub it out for local debug builds.

### 2. Add an LLM provider

First launch lands you in an empty chat. The app needs an LLM behind it
before anything works.

Open **Settings → Providers**, pick a provider (OpenAI, Google Gemini,
Anthropic, or any OpenAI-compatible endpoint), and paste your API key.
Then choose a model from the chat header.

> **Pixel 8 / 9 / 10 owners** can use the built-in **AICore** provider
> instead — it runs Gemini Nano fully on-device, no API key, no network.
> The AICore card ships with its switch **off**; flip it on once and pick a
> Nano model from the chat header. AICore requires the AICore app from the
> Play Store *and* enrolment in Google's GenAI Prompt-API early-access
> program. See the
> [latest release notes](https://github.com/ExTV/rikkahub-agent/releases/latest)
> for the exact steps.

At this point you have a normal RikkaHub chat. Everything below is opt-in.

### 3. Turn on the agent features you want

The fork's extra abilities live behind per-assistant tool toggles.

Open **Settings → Assistants → (tap your assistant) → Local Tools**. You'll
see categorized switches: device info, hardware control, personal data,
screen automation, SSH, Telegram bot, scheduled jobs, and more. Flip on
only what you actually want.

The first time the assistant uses a tool that needs a system permission
(location, contacts, accessibility, notification access, etc.), Android
will ask you to grant it. If you'd rather pre-grant everything in advance,
open **Settings → Permissions** — every permission the app uses is listed
there with a one-tap grant button.

### 4. (Optional) Talk to your assistant from anywhere via Telegram

If you enabled the **Telegram bot** tool in step 3, you can chat with your
assistant remotely.

1. Message [@BotFather](https://t.me/BotFather), send `/newbot`, follow the
   prompts, and copy the **bot token**.
2. Message [@userinfobot](https://t.me/userinfobot), send `/start`, and copy
   your **numeric Telegram user id**.

Now pick one of two paths:

**Path A — let the assistant configure itself**

Just chat with the assistant inside RikkaHub:

> *Set up the Telegram bot. Token is `<your token>`. My user id is
> `<your user id>`. Set me as the default chat. Enable the bot.*

It'll handle the rest.

**Path B — configure manually**

Settings → **Telegram bot** → paste token → set default chat → add your user
id to the allowlist → tap **Start**.

DM your bot. You should get a reply. Reboots, app kills, battery saver —
the bot survives all of them.

## How the safety model works

Everything new is opt-in, with three independent layers:

1. **Per-tool category toggles** — every new tool starts **off** behind a
   switch in each assistant's Local Tools page.
2. **Per-call approval** — when an enabled tool is side-effecting (shell, SSH,
   file writes, screen automation, Telegram outbound, MCP, notification action
   clicks, privacy reads), each call prompts before running. You pick **Allow**,
   **Allow for this chat**, **Always Allow**, or **Deny**. Same flow over
   Telegram via inline keyboards.
3. **HARDLINE floor** — `rm -rf /`, `mkfs`, `dd of=/dev/sd…`, fork bombs,
   `shutdown`/`reboot`/`init 0|6`, raw block-device redirects, and known
   shell-eval bypass forms (`bash -c "…"`, `base64 -d | sh`, `printf '\xNN' | sh`,
   `eval $(…)`) are blocked unconditionally. Always Allow doesn't override
   them. Cron headless mode doesn't override them. **There is no UI for
   bypassing HARDLINE** — that's the point.

The Telegram bot ignores everyone unless you explicitly allowlist their user
id. The notification forwarder ships with an empty whitelist — nothing leaves
your phone until you pick the apps to forward. Permissions for sensitive
surfaces (location, contacts, accessibility, notification access) are granted
by you, on Android's terms, the first time a tool needs them.

If you trust the agent fully and want zero prompts, **Settings → Tool
approvals → "I AM STUPID"** flips a global auto-approve. HARDLINE still
applies. The settings page shows a red banner whenever it's on so you can't
forget.

If you don't turn anything on, nothing changes from upstream behavior.

## Design principles

This fork is intentionally conservative about the UI. New features blend into
existing settings pages using the same components, spacing, and typography.
The single new top-level page (Telegram bot) follows the same pattern as
RikkaHub's existing WebServer page. The goal: nothing should feel bolted on.

## Status

**Shipped:**

- Android device tools (47+) covering battery, audio, telephony, WiFi, sensors,
  storage, toast, notifications, share, torch, vibrate, brightness, volume,
  media, download, location, contacts, call log, SMS inbox, camera, mic,
  speech-to-text, fingerprint, app launcher, URL opener, wake screen.
- Screen automation (11 tools): tap, long-press, swipe, scroll, read window
  tree, find/click node, set text, global action, screenshot, wake screen.
- SSH (8 tools) with parallel network probing, partial-file cleanup on SFTP
  failure, persistent host-key trust, stdout/stderr truncation, explicit
  command-timeout envelopes.
- Telegram bot (14 tools + 7 built-in slash commands + persistent custom
  commands + interactive `/model` picker + token-usage footer + HTML markdown
  rendering + sub-second streaming + token-revocation auto-disable + invalid-
  token notification + exponential backoff on transient errors). Runs as a
  `specialUse` foreground service (no daily time cap, unlike `dataSync`'s
  Android 14+ 6-hour limit which crashed the bot in earlier builds), with a
  defense-in-depth periodic 30-min health probe that re-starts the service
  if anything else (OEM aggressive task killers on Xiaomi/Samsung/Honor,
  OOM kills, app standby) takes it down.
- Persistent scheduled jobs — **7 LLM tools**, two modes (`llm` / `direct`),
  two timing types (`once` / `cron`), full 5-field UNIX cron with nicknames +
  `@every` aliases, IANA timezones, `start_at` / `end_at` bounds, `max_runs`,
  catchup policy (`skip` / `fire_once` / `fire_all`), `trigger_job_now`,
  `get_job_history` with run-history table (last 100 rows per job, FIFO).
  Boot recovery includes catchup + stranded-row sweep with single aggregate
  notification (no storm). Direct-mode actions HARDLINE-checked at create
  time AND at every fire. Replay-safe `max_runs` derived from history count,
  not a cached counter. WorkManager-backed; survives reboots, app kills,
  battery saver. Headless execution with hardline still enforced.
- Notification listener (5 tools + per-app whitelist + auto-forward to
  default Telegram chat + post-success dedup).
- On-device Gemini Nano via AICore — opt-in (off by default).
- Termux integration with capture mode + setup verification UI.
- Default agent skill seeded on first launch.
- **Tool-approval system**: 3-tier scopes (Once / Allow for chat / Always),
  HARDLINE floor with 37 unit tests, MCP-tool argument scanning, in-app +
  Telegram surfaces with parity, /stop and /new edit stale keyboards in place.
- **"I AM STUPID" YOLO toggle** in Settings → Tool approvals for users who
  trust the agent fully. HARDLINE still applies.
- **Privacy + integrity**: secret redaction in logcat, backup rules excluding
  credentials, system-prompt-instead-of-user-message agent context,
  idempotency on Approved-but-unexecuted tool resume, per-conversation mutex
  on state mutations, atomic StateFlow updates, hydration-from-disk on every
  approval entry point so process kills don't leave dead-locked sessions.

**On the roadmap:** SMS send, NFC, USB, Wallpaper, Keystore, Infrared.
Pattern-based dangerous-command detection layered on top of tool-name approval
gating. SSH interactive shell. MCP control tools. Sub-agents. Tasker-class
workflows with conditional triggers. User-memory tools. External automation
intent API for Tasker / MacroDroid integration. Translation pass for new
English strings.

## Contributing

Feature work happens here. For bug fixes that affect upstream RikkaHub
behavior, please open the PR on
[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub).

This fork is unaffiliated with the original RikkaHub maintainers. All credit
for the underlying chat client, provider abstraction, and UI design goes to
the upstream team.

## Sponsors (upstream)

<div align="center">
  <img src="app/src/main/assets/icons/aihubmix-color.svg" alt="Aihubmix" width="50" />
  <p>Upstream sponsor — <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a>
  for one-stop access to OpenAI, Claude, Gemini, DeepSeek, Qwen, and more.</p>
</div>

## License

[License](LICENSE) — inherited from upstream.
