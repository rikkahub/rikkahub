---
name: morning-briefing
description: Compose the user's morning summary — current weather, today's calendar, unread email count, scheduled jobs ahead, and any battery/storage warnings. Output one short paragraph so the user can read it in 10 seconds.
allowed-tools: get_time_info get_battery_status get_storage_info list_active_notifications list_recent_notifications get_jobs_history list_call_log get_location launch_app read_window_tree
---

# Morning briefing

Produce a single short paragraph that tells the user everything they need to know to start their day.

## When to use

The user asks "what's my day look like", "morning briefing", "good morning what's on the agenda", "summary please". Or, you're firing this from a workflow that runs every weekday at 7 AM.

## Steps

Run all reads in parallel where the tool surface allows; assemble at the end.

1. **Time anchor.** `get_time_info` — confirm the local date / weekday. The greeting depends on it ("Friday morning" vs "Saturday morning" vs holiday-named).
2. **Device hygiene.**
   - `get_battery_status` — call out only if level < 30% or charging is off when the user normally plugs in overnight.
   - `get_storage_info` — call out only if free < 5%.
3. **Communications.**
   - `list_active_notifications` filtered to packages the user has whitelisted in `notification_listener` settings — group by package, count unread.
   - `list_call_log(type = "missed", limit = 5)` — surface anyone the user missed since their last interaction.
4. **Calendar / weather.** Both are app-driven. Pick whichever calendar app the user uses (`com.google.android.calendar`, `com.microsoft.office.outlook`, etc.) — `launch_app` + `read_window_tree` on the day view, pull today's events as text. Weather: same idea via the OEM weather app or the user's preferred (Pixel Weather, Google, AccuWeather).
5. **Scheduled jobs.** `get_jobs_history(limit = 5, since_ms = <last 24h>)` — surface anything that failed overnight.
6. **Compose the paragraph.** Lead with the greeting + date. Then the warnings (if any). Then the meetings (if any). Then the comms summary. End with a one-line "anything else?" so the user can chain.

## Output shape

- ≤4 sentences total. Don't pad.
- Plain text — no markdown headers. The user is reading this on a phone or hearing it through TTS.
- If everything is normal (battery fine, storage fine, no missed calls, no urgent notifications, calendar empty), say so in one sentence and stop.

## Failure modes

- **Calendar app not installed / accessibility view doesn't render structured text.** Skip the meetings section; mention "I couldn't read your calendar — open it yourself if you've got something today".
- **Weather requires location and the user denied it.** Skip silently; don't bug the user about permissions in a briefing context.
- **Notification listener disabled.** Mention it once: "By the way, your notification listener is off so I can't see app activity — turn it on in Settings if you want me to include that in tomorrow's briefing."

## Don't

- Don't use this skill to read every notification verbatim. If there are 47 unread emails, say "47 unread email" not 47 lines.
- Don't quote any message body — preview titles + senders only. Email previews regularly contain reset links, OTPs, and other things the user wouldn't want narrated.
- Don't speculate about the user's day from calendar metadata ("looks busy!"). Stick to facts.
