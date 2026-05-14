<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent

**Your phone, automated.**

A fork of [RikkaHub](https://github.com/rikkahub/rikkahub) that turns the native Android LLM chat client into a real on-device agent: 80+ device tools, AI-authored workflows, scheduled jobs, an in-app browser the AI drives, SSH, screen automation, file manager, music player, voice transcription, downloadable on-device LLMs, and a remote Telegram bot. All opt-in.

<p>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/v/release/ExTV/rikkahub-agent?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/downloads/ExTV/rikkahub-agent/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/stargazers"><img src="https://img.shields.io/github/stars/ExTV/rikkahub-agent?style=flat-square&color=yellow" alt="Stars" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
</p>

<a href="https://extv.github.io/rikkahub-agent/"><strong>Website</strong></a> ·
<a href="https://github.com/ExTV/rikkahub-agent/releases/latest"><strong>Download</strong></a> ·
<a href="#-features"><strong>Features</strong></a> ·
<a href="#-quick-start"><strong>Quick Start</strong></a> ·
<a href="#-building-from-source"><strong>Build</strong></a>

</div>

---

## Why

Vanilla LLM chat apps can answer questions. They can't open your apps, send your messages, watch your notifications, run scheduled jobs, or SSH into your server. RikkaHub Agent can. Tell it what to do in plain language, walk away, and it runs in the background, on your phone, on your terms.

> *"Every weekday at 9am, summarize my unread WhatsApp into one Telegram message."*
>
> *"If my home server's disk fills up, ping me."*
>
> *"Watch my notifications. If anything from my boss comes in, forward it to Telegram. Quietly ignore the rest."*
>
> *"Find the PDF on my phone that mentions 'invoice' and read me the first paragraph."*
>
> *"Take a screenshot every 30 minutes for the next 4 hours so I can see what I actually did all afternoon."*
>
> *"Use Termux to build me a webpage listing everything you can do, then open it in my browser."*
>
> *"When I plug in headphones at home WiFi after 7pm, start my evening playlist."*
>
> *"Open my router's admin page, sign in with the saved password, and tell me which devices are eating the most bandwidth right now."*
>
> *"Spin up two researches in parallel: one finds the cheapest one-way flight to Tokyo this month, the other lists hotels in Shibuya under $100. Tell me when both finish."*

Each of those is a one-line setup. The phone runs them in the background while you live your life.

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### Control your phone

Ask the AI to tap, swipe, scroll, type, take screenshots, open apps, turn the torch on, change brightness or volume, post a notification, vibrate, share something, or read your battery, WiFi, signal, location, sensors, contacts, and SMS. It can also send an SMS, set the wallpaper, read and write NFC tags, sign and encrypt data with the Android Keystore, reach external storage and SD cards, and zip or unzip archives. Over 80 tools, all built into Android, no extra apps required. Each one stays off until you flip it on.

</td>
<td width="50%" valign="top">

### Telegram bot

Talk to your assistant from anywhere. Set up a private Telegram bot in a minute, then chat with it like a contact. Send a question, a photo, a PDF, or a voice note. It can run on your behalf while you're at work, while you sleep, or while you're driving. Approval prompts use simple Yes/No buttons in the chat.

</td>
</tr>
<tr>
<td valign="top">

### In-app browser

The agent has a real browser built into the app. Watch it open URLs, click through cookie banners, fill in search boxes, scroll, and read the page back to you. Or send it on errands from Telegram. It streams a fresh screenshot to your chat after every step. There's a floating chat pill on the browser screen so you can keep talking to the AI without ever leaving the page. Built-in article extraction and diff-after-action keep the token cost low even on long browse sessions.

</td>
<td valign="top">

### Workflows

Tasker-style automation, but the AI writes the rules for you. Just describe the trigger and the action: *"when I get home, turn the ringer off"*; *"every weekday at 8am if battery is over 50%, check my email and ping me if anything's urgent"*. 19 triggers (WiFi, Bluetooth, headphones, geofence, app launch, notifications received, time, charging, screen on/off, and more) and 14 conditions (battery thresholds, sunrise/sunset, day-of-week, current foreground app, screen state) decide when each one fires. Receivers register only when a workflow actually needs them, so battery drain stays minimal.

</td>
</tr>
<tr>
<td valign="top">

### Schedule anything

Set tasks to run on a schedule and forget about them. "Every Monday morning at 8", "every two hours", "next Friday at 3pm". The phone keeps everything running through reboots and battery saver. Pick how each task fires: let the AI think at the moment and decide what to do (good for "watch X and ping me if Y"), or pre-bake a fixed action that runs without using AI tokens (good for plain reminders).

</td>
<td valign="top">

### Find and manage files

The AI has its own file manager. Find files, read them, save new ones, copy, move, rename, delete. Same things you'd do in a regular file manager, except you describe what you want and it does it. "Find every PDF mentioning 'invoice' on my phone" works in one sentence. System folders that don't belong to you are off-limits, even if you ask.

</td>
</tr>
<tr>
<td valign="top">

### SSH from your pocket

Save your servers once and the AI can SSH into any of them on demand. Run a command, upload a file, pull down a backup, check disk space, tail a log. Works whether you're on WiFi or cell. Watch your home server from a coffee shop without opening a terminal.

</td>
<td valign="top">

### Termux + voice transcription

If you have Termux installed, the AI can run real Linux commands on your phone: installing packages, building software, running scripts. On top of that, voice notes you send in Telegram get transcribed automatically. Everything runs on your phone, no cloud transcription, no API key, no internet needed.

</td>
</tr>
<tr>
<td valign="top">

### Music + media

Ask for music and the AI plays it through Android's normal media controls: lock-screen art, headphone keys, the works. Pause, resume, lower the volume for a meeting and bring it back later, all from chat or Telegram. Even after a force-stop the AI can pick up where you left off, same track, same position, via a snapshot fallback. No "you killed the player so it's gone forever". Your queue survives.

</td>
<td valign="top">

### Skills

Drop a Markdown skill file into the app and the AI gains a new playbook it'll follow step-by-step: auto-reply to a contact, summarise a notification stack, or run a JavaScript mini-app whose result opens right in the in-app browser. A bundled featured catalog ships with a QR generator, a Wikipedia query box, a piano you can play, an interactive map, and more. Add new skills from a URL, a markdown file you share into the app, or pick from the bundled catalog.

</td>
</tr>
<tr>
<td valign="top">

### Sub-agents

For long tasks the main assistant can dispatch a focused **sub-agent** into a clean side-context, optionally on a smaller and cheaper model. Two or more run in parallel: one researches a topic while another updates your server. Each result comes back as a single summary so the main chat doesn't drown in irrelevant tool output, and `/stop` cascades cancellation through every active child in one tick.

</td>
<td valign="top">

### Doctor

A built-in health checkup for the app. Tap Settings, then Doctor, and it runs a top-to-bottom audit of permissions, background services, database integrity, network, Termux, and diagnostics. Missing something? Tap the auto-fix button next to the row to grant the permission, restart the service, or rebuild the chat search index. The same report runs from Telegram via `/doctor` for remote troubleshooting. Smart enough to skip permissions you haven't enabled any tools for.

</td>
</tr>
<tr>
<td valign="top">

### MCP servers

Connect the assistant to [Model Context Protocol](https://modelcontextprotocol.io) servers and the AI gains whatever tools those servers expose. The AI can add, update, and manage MCP connections itself — every connection change is approval-gated, so a server can't be wired in behind your back.

</td>
<td valign="top">

### Notifications + external triggers

Pick which apps the AI is allowed to watch, and it can read, summarize, and forward incoming notifications — the whitelist starts empty, so nothing leaves your phone until you choose. Other apps (Tasker, automation tools, ADB) can also hand the agent a task through the External Automation Intent API, so RikkaHub Agent slots into automation flows you already run.

</td>
</tr>
<tr>
<td colspan="2" align="center" valign="top">

### Safety + privacy

Three layers of protection, in order of strictness:

**Per-assistant toggles**. Every tool starts off. Flip on only what you want.

**Per-call approval**. Tools that change something on your phone ask before running. Allow once, for this chat, always, or deny.

**HARDLINE floor**. A short list of genuinely dangerous commands (wipe everything, reboot, fork bombs, system file destruction, and known shell tricks to bypass the rule) is blocked unconditionally. Even if you accidentally tell the AI to do one of these, it won't.

Plus: passwords and API keys never make it into log files. The Telegram bot ignores everyone except people you put on its allowlist. Cloud backups skip your saved server credentials and bot token. The notification listener starts with an empty whitelist, so nothing leaves your phone until you pick the apps to forward.

</td>
</tr>
</table>

---

## 🚀 Quick Start

1. **Install**: download the latest **`*-release.apk`** from [Releases](https://github.com/ExTV/rikkahub-agent/releases/latest). Allow install from unknown sources, then open. (One-time note: if you still have an old debug build of RikkaHub Agent installed, uninstall it first — the release build is signed differently and won't upgrade over it.)
2. **Add an LLM provider**: Settings, then Providers, pick one, paste your API key. For fully on-device inference with no key and no network, open the **Local · LiteRT** provider and download a local model (Gemma, Qwen) — it runs on any device and uses the GPU automatically where supported. Pixel 8/9/10 users can also flip on the built-in **AICore** card for Gemini Nano.
3. **Turn on what you want**: Settings, then Assistants, tap your assistant, then **Local Tools**, and flip the categories you want enabled.
4. **(Optional) Telegram bot**: message [@BotFather](https://t.me/BotFather) with `/newbot` to get a bot token, then [@userinfobot](https://t.me/userinfobot) with `/start` to get your numeric Telegram user id. Then just say to the assistant in chat: *"Set up the Telegram bot. Token is `<your token>`. My user id is `<your id>`. Set me as the default chat. Enable it."* It'll handle the rest.

If you don't turn anything on, the app behaves exactly like vanilla RikkaHub.

---

## 📋 Requirements

|              |                                                              |
| ------------ | ------------------------------------------------------------ |
| Architecture | arm64 or x86_64                                              |
| Android      | 8.0+ (API 26), targets API 37                                |
| Storage      | ~80 MB app                                                   |
| LLM provider | OpenAI, Google, Anthropic, Ollama, or any OpenAI-compatible endpoint. OR Gemini Nano via AICore on Pixel 8/9/10+ |

---

## 🌍 Languages

The interface ships in **English, 简体中文, 繁體中文, 日本語, 한국어, Русский, and العربية**. The app follows your system language automatically and falls back to English for anything not yet translated. Right-to-left languages render correctly in chat and markdown — code blocks stay left-to-right — and Arabic, Persian, and Urdu are available as translator languages.

---

## 🔧 Building from source

Requires the [bun](https://bun.sh) JavaScript runtime on PATH. The build chain
runs `bun install` and `bun run build` in `web-ui/` to produce the in-app web
UI bundle before packaging the APK.

```bash
git clone https://github.com/ExTV/rikkahub-agent.git

cd rikkahub-agent

./gradlew :app:installDebug   # build + install on a connected device
```

---

## 🙏 Credits

Stands on the shoulders of giants:

| Project                                                              | Role                                                |
| -------------------------------------------------------------------- | --------------------------------------------------- |
| [RikkaHub](https://github.com/rikkahub/rikkahub)                     | The beautiful upstream chat client this forks       |
| [cron-utils](https://github.com/jmrozanec/cron-utils)                | 5-field cron parser for the scheduler               |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp)              | On-device speech-to-text via Termux                 |
| [Termux](https://github.com/termux/termux-app)                       | Shell + package manager the agent uses for shell-out |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch)                 | Native SSH client                                   |
| [FlorisBoard](https://github.com/florisboard/florisboard)            | Base for the optional [agent-keyboard](https://github.com/ExTV/agent-keyboard) companion |

This fork is unaffiliated with the upstream RikkaHub maintainers. All credit for the underlying chat client, provider abstraction, and UI design goes to the upstream team.

---

## 📄 License

Inherited from [upstream](https://github.com/rikkahub/rikkahub), see [LICENSE](LICENSE).
