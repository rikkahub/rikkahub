<div align="center">
  <img src="docs/icon.png" alt="RikkaHub Agent" width="120" />

  <h1>RikkaHub Agent</h1>

  <p><strong>Your Android phone, but it can actually <em>do</em> things.</strong></p>

  <p>
    A fork of <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a> that turns the
    beautiful native Android LLM chat client into a real on-device agent —
    with optional Telegram remote control, scheduled jobs, SSH, screen automation,
    and deep device access. Pixel-perfect with the original UX.
  </p>

  <p>
    <a href="https://github.com/rikkahub/rikkahub">Upstream</a> ·
    <a href="https://github.com/ExTV/rikkahub-agent">This fork</a> ·
    <a href="#getting-started">Getting started</a> ·
    <a href="#what-can-it-do">What can it do</a>
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
thinks, summarizes which tools it used, and remembers your conversation across
messages.

### Run jobs while you sleep

Schedule one-off or recurring prompts. *"Every Monday morning, summarize my
unread notifications and DM me."* The agent stores these jobs persistently —
they survive reboots, app kills, and battery saver — and reports back through
your Telegram chat.

### Operate your phone like you would

With a single permission grant, the agent can tap, swipe, scroll, type, take
screenshots, and read what's on screen. Vision-capable models can actually see
the screen and decide what to do next.

### Reach into your device

Need the agent to check battery, read your location, send a notification, flick
the torch on, adjust volume, scan WiFi, list contacts, or save a file?
There's a tool for that — over 35 of them, all native Android, no third-party
apps required. Each is opt-in per assistant.

### SSH from your pocket

Saved hosts, one-shot commands, file upload/download, proper host-key checking.
Ask the assistant to log into your server and tail a log — it does.

### Live notification awareness

The agent can read incoming notifications and (optionally) auto-forward
whitelisted apps to Telegram. WhatsApp pings, emails, calendar reminders —
relayed and summarized while you're away from your phone.

### Termux integration

Run shell commands inside Termux *with output captured*, so the model can
actually reason about what came back.

### On-device inference (Pixel 8/9/10)

First-class support for Gemini Nano via Android's AICore — runs entirely
on-device, no API key, no network. Tool calling works on-device too.

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

### Install

```bash
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent
./gradlew :app:installDebug
```

You'll need a `google-services.json` at `app/google-services.json` — either
your own from the Firebase console, or stub it out for local debug builds.

### Set up the Telegram bot (under 2 minutes)

1. Open Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot`,
   follow the prompts, and copy the **bot token**.
2. Message [@userinfobot](https://t.me/userinfobot), send `/start`, and copy
   your **numeric Telegram user id**.
3. In RikkaHub, open any assistant → **Local Tools** → **Network** → turn on
   **Telegram bot**.

Now pick one of two paths:

**Path A — let the assistant configure itself**

Just chat with the assistant:

> *Set up the Telegram bot. Token is `<your token>`. My user id is
> `<your user id>`. Set me as the default chat. Enable the bot.*

It'll handle the rest.

**Path B — configure manually**

Settings → **Telegram bot** → paste token → set default chat → add your user id
to the allowlist → tap **Start**.

DM your bot. You should get a reply.

That's it. Reboots, app kills, battery saver — the bot survives all of them.

## How the safety model works

Everything new is opt-in:

- New tools live behind toggles inside each assistant's settings.
- Side-effecting actions (messages, remote commands, file writes) require
  either a per-tool toggle or in-the-moment user approval.
- The Telegram bot ignores everyone unless you explicitly allowlist them.
- The notification forwarder ships with an empty whitelist — nothing leaves
  your phone until you pick the apps.

If you don't turn anything on, nothing changes from upstream behavior.

## Design principles

This fork is intentionally conservative about the UI. New features blend into
existing settings pages using the same components, spacing, and typography.
The single new top-level page (Telegram bot) follows the same pattern as
RikkaHub's existing WebServer page. The goal: nothing should feel bolted on.

## Status

**Shipped:** Android device tools (35), SSH, Telegram bot with live streaming
replies and slash commands, persistent scheduled jobs, screen automation (11
tools), Termux integration with output capture, notification awareness with
Telegram routing, on-device Gemini Nano provider, default agent skill seeded
on first launch.

**On the roadmap:** SMS send, NFC, USB, Wallpaper, Keystore, and Infrared.

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
