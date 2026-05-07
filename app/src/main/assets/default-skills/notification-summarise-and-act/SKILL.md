---
name: notification-summarise-and-act
description: Read the recent / active notification stream, group by app, summarise what's actually happening, and propose specific next actions. Used when the user asks "what's going on" or returns to their phone after a few hours.
allowed-tools: list_recent_notifications list_active_notifications dismiss_notification notification_action_click launch_app read_window_tree get_time_info
---

# Notification summarise + act

Compress the user's notification noise into a short briefing with concrete suggestions. The goal is "skim in 5 seconds, decide in 10".

## When to use

- "What did I miss" / "what's going on" / "anything important"
- The user just woke up / opened the phone after a meeting
- A workflow fires this on a schedule (e.g. "every 2h while screen is off, summarise notifications")

## Steps

1. **Time anchor.** `get_time_info` — note "since when" the user last interacted (best-effort: use the freshest notification's `post_time` as the lower bound).
2. **Read the stream.**
   - `list_active_notifications` — what's currently visible in the shade.
   - `list_recent_notifications(limit = 50)` — the ring buffer; covers the last few hours.
3. **Group by package.** For each package, count entries, pick the most-recent title + preview. Skip packages the user hasn't whitelisted in `notification_listener` settings — those are noise.
4. **Categorise.**
   - **Actionable** — anything that needs a reply, response, or decision (chat messages, calendar invites, package deliveries, account alerts).
   - **Informational** — newsletters, app updates, social media, marketing.
   - **Urgent** — bank fraud alerts, missed calls, system warnings, OTP-style codes.
5. **Compose the briefing.**
   - Lead with urgent (≤2 lines).
   - Then actionable (1 line per source, with the count + sender if obvious).
   - Drop informational entirely UNLESS the user usually wants those (check memory).
   - Total length ≤6 short sentences.
6. **Propose actions.** End with up to 3 concrete suggestions:
   - "Reply to <Bob> in Telegram?"
   - "Open the bank alert?"
   - "Dismiss the 14 marketing pings?"
   The user can say yes/no/skip and you do the next step.
7. **If the user says yes to a dismiss-marketing kind of action**, call `dismiss_notification(key = ...)` for each unimportant entry. Do NOT auto-dismiss without confirmation.

## Tools used

- `list_recent_notifications`, `list_active_notifications`
- `dismiss_notification`, `notification_action_click`
- `launch_app`, `read_window_tree` (to peek at one chat if the user picks a suggestion)
- `get_time_info`

## Failure modes

- **Notification listener disabled.** Tell the user once and offer the Settings deep-link path: "Notification listener is off — I can only see what's in `list_active_notifications`. Toggle it on in Settings → Notifications if you want me to track these properly."
- **No recent notifications.** Fine — say so in one line ("Nothing new — last 3h is quiet") and stop. Don't manufacture content.
- **OTP-shaped content in titles/previews.** Mark as urgent, but DON'T quote the code. Say "your bank sent a verification code — it's in the notification on your phone".

## Don't

- Don't dismiss anything without explicit user yes.
- Don't read message bodies aloud / forward them — that belongs to `smart-forward` if the user wants it.
- Don't summarise if the result would be longer than the raw notifications. Just list them.
- Don't classify a notification as "urgent" because of capslock or exclamation marks — apps use those liberally.
