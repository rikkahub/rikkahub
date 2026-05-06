<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent

**Your phone, automated.**

A fork of [RikkaHub](https://github.com/rikkahub/rikkahub) that turns the native Android LLM chat client into a real on-device agent: 60+ device tools, scheduled jobs, SSH, screen automation, file manager, voice transcription, and a remote Telegram bot. All opt-in.

<p>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/v/release/ExTV/rikkahub-agent?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/downloads/ExTV/rikkahub-agent/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/stargazers"><img src="https://img.shields.io/github/stars/ExTV/rikkahub-agent?style=flat-square&color=yellow" alt="Stars" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ExTV/rikkahub-agent?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
</p>

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

Each of those is a one-line setup. The phone runs them in the background while you live your life.

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### Control your phone

Ask the AI to tap, swipe, scroll, type, take screenshots, open apps, turn the torch on, change brightness or volume, post a notification, vibrate, share something, or read your battery, WiFi, signal, location, sensors, contacts, and SMS. Over 60 tools, all built into Android, no extra apps required. Each one stays off until you flip it on.

</td>
<td width="50%" valign="top">

### Telegram bot

Talk to your assistant from anywhere. Set up a private Telegram bot in a minute, then chat with it like a contact. Send a question, a photo, a PDF, or a voice note. It can run on your behalf while you're at work, while you sleep, or while you're driving. Approval prompts use simple Yes/No buttons in the chat.

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

1. **Install**: download the latest `*-universal-debug.apk` from [Releases](https://github.com/ExTV/rikkahub-agent/releases/latest). Allow install from unknown sources. Open.
2. **Add an LLM provider**: Settings, then Providers, pick one, paste your API key. (Pixel 8/9/10 users can flip on the built-in **AICore** card for fully on-device Gemini Nano. No key, no network.)
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

## 🔧 Building from source

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

This fork is unaffiliated with the upstream RikkaHub maintainers. All credit for the underlying chat client, provider abstraction, and UI design goes to the upstream team.

---

## 📄 License

Inherited from [upstream](https://github.com/rikkahub/rikkahub), see [LICENSE](LICENSE).

<div align="center">
<sub>Built by <a href="https://github.com/ExTV">@ExTV</a></sub>
</div>
