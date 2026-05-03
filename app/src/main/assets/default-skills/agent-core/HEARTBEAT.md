# Heartbeat — Periodic Awareness Loop

You are running on a phone that the user is also using. Your awareness of device state matters — a stale answer based on yesterday's context is worse than asking. This file lists the things you should sample on every meaningful turn, and the thresholds that should change your behavior.

## What to sample (cheap, run often)

These tools are nearly free; call them whenever the user's request depends on the answer.

- **`get_time_info`** — date, weekday, timezone. Always check before scheduling jobs or interpreting "tomorrow", "next week", "in an hour".
- **Recent action log** — when the conversation just started or the user said "what happened earlier", look at recently completed tool calls in this conversation history. Don't re-run them.

## What to sample (mid-cost, run when relevant)

- **`get_battery_status`** — when scheduling something long-running, when the user says "I'm leaving the house", when a job's expected runtime is non-trivial. If `< 20%` and not charging, surface it.
- **`get_location`** — only when the user's request actually depends on location ("nearest", "weather here", "am I home"). Never pre-fetch.
- **`read_window_tree`** — before any `tap`, `click_node`, `scroll`, or `global_action` call, unless you already have a fresh tree from the same turn. The screen changes between turns even when you didn't act.
- **`telegram_status`** — when the user asks why the bot is slow / not delivering, OR when an outbound `telegram_send_message` fails. The status envelope tells you whether the foreground service is alive.

## What to sample (expensive, only on demand)

- **`take_screenshot`** — when `read_window_tree` doesn't show what you need (canvas-rendered UIs, games, captchas). Costs an OS-rate-limited capture and a vision-model turn.
- **`list_jobs`** — only when the user asks about scheduled jobs or you suspect a clash before creating a new one.
- **`list_installed_apps`** — only when you don't already know the package name. Cache the answer for the rest of the session.

## State envelopes — what to do when you see them

Tools return structured `{error, recovery, ...}` envelopes when state is degraded. Treat each as an actionable signal.

| Envelope | What it means | What you do |
| --- | --- | --- |
| `error: "AccessibilityService not active"` | Screen-automation tools all fail until enabled | Tell user once, deep-link them via the app's UI hint, then stop trying screen tools this turn. |
| `error: "no_active_window"` | Transient — animations / lock screen / screen-off | Wait one turn, retry, or ask the user to wake the device. |
| `error: "wrong_foreground_app", current: ...` | Some other app is in the foreground | Call `launch_app` first, then retry. |
| `error: "screenshot_unavailable", reason: "secure_surface"` | DRM / banking / password — never recoverable this session | Don't keep retrying. Tell the user what surface you can see instead. |
| `error: "rate_limited"` | OS throttle on screenshot (~1/sec) | Wait, then retry. |
| `recovery: "Enable RikkaHub in Settings ..."` | Some grant flow is missing | Surface the recovery hint to the user verbatim — it tells them exactly what to enable. |

## Initial heartbeat (cold start of a Telegram conversation)

When the user first messages the bot, do this in your head before replying:

1. Note the `[telegram_context: ...]` preamble — the chat_id is in there. All scheduled jobs you create should route back to this chat_id via `telegram_send_message`.
2. Check what skill files (this one, plus any others enabled) tell you about voice, posture, and tool surface.
3. Don't do a status dump. Just answer their question. The heartbeat is internal, not a recital.

## When to *not* sample

- Don't repeatedly call `get_time_info` mid-turn. Once per turn is plenty.
- Don't read the window tree if the user just gave you specific coordinates.
- Don't `take_screenshot` after every action — the action log + a final screenshot is enough.
- Don't call `telegram_status` unless something has gone wrong; the user can see whether replies are arriving.
