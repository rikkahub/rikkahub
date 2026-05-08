---
name: 🌐 In-app browser bug
about: Report a problem with the in-app browser, the AI driving it, or Telegram-streamed screenshots
title: '[Browser] '
labels: bug, browser
assignees: ''

---

## What happened

What did you ask the AI to do, and what actually happened in the browser? Did a page render blank, did a click miss, did the bot send all-white screenshots, did the address bar refuse a URL, did the mini-chat overlay misbehave?

## What you expected

What should have happened instead?

## Steps to reproduce

1. ...
2. ...
3. ...

If a specific URL triggers it, paste the URL. If it's site-specific (Hugo, Cloudflare, mixed-content, single-page apps), mention that.

## Mode

- [ ] Foreground (Settings → Browser → Open browser, or AI launched it visibly)
- [ ] Headless (Telegram bot or scheduled job drove it; you got streamed screenshots)
- [ ] Skill webview card (a JavaScript skill output opening into the browser)

## Screenshot or screen recording

Attach a clip or screenshot. For headless mode, the streamed photo from your Telegram chat is gold.

## Logs

If the app reaches the chat, ask the assistant: *"generate a bug report"*. It produces a redacted ZIP via the `generate_bug_report` tool. Attach it here.

If you can attach an adb logcat: `adb logcat -d | grep -E "RikkaWebView|BrowserController|RikkaWebViewConsole" > browser.log` and attach `browser.log`.

## Version + device

- App version: (Settings → About, e.g. `v2.1.16-agent.0`)
- Android version: (e.g. Android 14)
- Device: (e.g. Pixel 8 Pro)
