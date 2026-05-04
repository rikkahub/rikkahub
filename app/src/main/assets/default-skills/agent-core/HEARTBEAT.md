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
- **`list_recent_notifications`** — when the user asks "what notifications did I miss", "what's that ping", or anything implying notification history. Cheap (in-memory ring buffer). The auto-route forwarder already pushes whitelisted apps to Telegram in real time; the LLM does not need to poll — answer based on what's already in the chat history when relevant.

## What to sample (expensive, only on demand)

- **`take_screenshot`** — when `read_window_tree` doesn't show what you need (canvas-rendered UIs, games, captchas). Costs an OS-rate-limited capture and a vision-model turn.
- **`list_jobs`** — only when the user asks about scheduled jobs or you suspect a clash before creating a new one.
- **`list_installed_apps`** — only when you don't already know the package name. Cache the answer for the rest of the session.

## State envelopes — what to do when you see them

Tools return structured `{error, recovery, ...}` envelopes when state is degraded. Treat each as an actionable signal.

| Envelope | What it means | What you do |
| --- | --- | --- |
| `error: "AccessibilityService not active"` | Screen-automation tools all fail until enabled | Tell user once, deep-link them via the app's UI hint, then stop trying screen tools this turn. |
| `error: "no_active_window"` | Transient — animations / lock screen / screen-off | Call `wake_screen` first; if `keyguard_secure:true`, ask the user to unlock. Otherwise retry one turn later. |
| `error: "wrong_foreground_app", current: ...` | Some other app is in the foreground | Call `launch_app` first (it will auto-wake), then retry **without** the `package_name` guard if the user is actively viewing RikkaHub. |
| `error: "launch_did_not_focus", current_foreground: ...` | `launch_app` dispatched but the OS did not move focus (usually because the user is physically looking at RikkaHub's chat) | Do NOT pass `package_name` to the next `read_window_tree` — drop the guard and read whatever IS on screen, or surface `recovery` to the user verbatim and stop trying to drive the target app this turn. |
| `error: "node_not_editable"` | `set_text` target is not an input field | If the surface is Termux or a terminal, switch to `termux_run_command`. Otherwise re-locate the actual input. |
| `error: "termux_not_installed"` / `"termux_permission_denied"` | Termux missing or `allow-external-apps` not set | Surface the recovery hint to the user verbatim — it tells them exactly what to fix. |
| `error: "screenshot_unavailable", reason: "secure_surface"` | DRM / banking / password — never recoverable this session | Don't keep retrying. Tell the user what surface you can see instead. |
| `error: "rate_limited"` | OS throttle on screenshot (~1/sec) | Wait, then retry. |
| `recovery: "Enable RikkaHub in Settings ..."` | Some grant flow is missing | Surface the recovery hint to the user verbatim — it tells them exactly what to enable. |
| `error: "notification_listener_not_bound"` | Listener service unbound | Surface the recovery hint verbatim. The user must enable RikkaHub in Settings → Notification access. |
| `error: "requires_input"` (from notification_action_click) | The action needs typed input (RemoteInput) | Fall back to launch_app + set_text + click_node via screen automation. |
| `error: "loop_detected"` (from any tool) | The host app blocked your call because you repeated this exact tool with identical args 3+ times in this turn without progress | STOP retrying. Either change args meaningfully, switch to a different tool, or reply to the user with what you have. The `recovery` field tells you exactly what to try. |

## Loop avoidance — token-cost discipline

**Hard rule:** every tool call costs the user money. If a tool returns the same result twice in a row, calling it a third time will return `loop_detected` and you will have wasted three turns. Specific anti-patterns to avoid:

- **Browser typing:** Never drive Chrome's URL bar with `set_text`. The accessibility tree's editable target is unstable across Chrome's launch overlay, the Suggestions panel, and the omnibox. For searches use `open_url("https://www.google.com/search?q=…")`; for direct visits use `open_url("https://example.com")`. One tool call, done.
- **Terminal typing:** Never `set_text` into Termux. Use `termux_run_command` with capture mode.
- **Selector retries:** If `click_node(by=text, value="Send")` returned `no_match`, calling it again with the SAME `value` won't suddenly succeed. Try a different selector axis (`view_id_resource_name` if the app exposes one) or a different value.
- **Self-diagnostic spam:** Don't call `notification_status` / `telegram_status` mid-task "to make sure" — they are diagnostic tools, only useful when something already returned a not-bound envelope.
- **Re-reads with no action between:** After a successful `tap` / `click_node` / `swipe`, give the OS one beat before re-reading the tree. Reading the tree N times for the same on-screen state is wasted budget.
- **package_name guards after launch_app:** If `launch_app` returned `confirmed_foreground:false` or `error:"launch_did_not_focus"`, do NOT pass `package_name` to the next `read_window_tree` / `click_node` / `find_node` — those guards will keep returning `wrong_foreground_app` and you will loop. Drop the guard and read the screen as-is.
- **Clicking the N-th search result:** Don't fight the search-results page with `click_node` by text — the labels are often a mix of languages, ad markers, and rich snippets, and your selector will miss. If the user wants the top result, use `open_url("https://www.google.com/search?q=…&btnI=1")` (Google's "I'm Feeling Lucky" — lands directly on the first organic hit). If `btnI` doesn't fire, fall back to a coordinate `tap` near the top of the results area after one `read_window_tree`, not repeated text-selector retries.

When in doubt, stop early and reply with what you have. Let the user redirect. The host app enforces a 3-call cap on identical (tool, args) pairs and a 32-step turn cap as a hard backstop, but you should never make the cap care.

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
