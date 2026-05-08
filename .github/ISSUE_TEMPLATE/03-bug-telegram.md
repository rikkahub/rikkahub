---
name: 📨 Telegram bot bug
about: Report a problem with the Telegram bot, slash commands, attachment handling, or approval keyboards
title: '[Telegram] '
labels: bug, telegram
assignees: ''

---

## What happened

Did the bot stop replying? Did `/stop` not stop a turn? Did an approval keyboard never appear? Did a tool result never reach the chat? Did inbound voice / photo / document handling break? Did the bot start replying to people not on your whitelist?

## What you expected

## Steps to reproduce

1. ...
2. ...
3. ...

## Slash command (if relevant)

- [ ] `/start`
- [ ] `/help`
- [ ] `/new`
- [ ] `/stop`
- [ ] `/status`
- [ ] `/model`
- [ ] `/ratelimit`
- [ ] `/doctor`
- [ ] custom command (paste it)

## Bot status

Open Settings → Telegram bot. Is the foreground service running? Is the bot enabled? Token set? Default chat configured? Whitelist populated? Any error message visible?

Or in the bot, send `/status` and paste the output.

## Logs

In any chat, ask the assistant: *"generate a bug report"*. Attach the redacted ZIP. The Telegram token and authorization headers are scrubbed automatically.

If you can attach an adb logcat: `adb logcat -d | grep -E "TelegramBotService|TelegramBotClient" > telegram.log`.

## Version + device

- App version:
- Android version:
- Device:
- Bot service kill behaviour: does the bot survive force-stop? Does it survive a few hours of doze?
