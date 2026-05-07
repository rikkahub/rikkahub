---
name: auto-reply
description: Reply to an incoming message in any chat app on behalf of the user. Reads the visible conversation, drafts a context-aware reply, and sends it. Composes the notification listener, the accessibility tap/scroll/read tools, and the back-to-home global action.
allowed-tools: list_recent_notifications list_active_notifications launch_app read_window_tree find_node click_node set_text scroll global_action take_screenshot
---

# Auto-reply

Reply to an incoming message inside the originating chat app, without leaving the user's home loop.

## When to use

The user says something like "reply to <person> for me", "send <person> 'on my way'", "draft a reply to the last message", or you spot an unread chat (Telegram / WhatsApp / Signal / Messages / Slack) in `list_recent_notifications` and the user has asked you to handle replies autonomously.

Do NOT use this skill if the user just asked you to send a brand-new message to someone you haven't surfaced yet — for that, use `telegram_send_message` (Telegram only) or open the app yourself.

## Steps

1. **Identify the incoming chat.** Call `list_recent_notifications` and pick the most recent unread row whose package is the messaging app. Note `package_name`, `title` (usually the contact name), `text` (preview of the last message), `key`.
2. **Open the app.** Use `notification_action_click` with the entry's primary action if available; otherwise `launch_app(package_name = "<x>")` and the app will land on the chats list.
3. **Open the contact's thread.** Read `read_window_tree`, `find_node` for the contact name's text node, `click_node`. If the chat is already open, skip.
4. **Read the visible context.** Call `read_window_tree` on the chat screen. Scroll up once with `scroll(direction = "up")` if the most recent few messages aren't visible. Pull the last 3-5 messages out of the tree as plain text.
5. **Draft the reply.** Match the user's tone (you have memory; check `enableMemory`). Keep it short. If the incoming message is a question, answer it. If it's a status update, acknowledge it. If it's a request, decide whether the user can fulfil it now or needs to defer.
6. **Send.** `find_node` for the message-input field, `set_text` with your draft, `find_node` for the send button (looks like a paper-plane / arrow), `click_node`.
7. **Confirm.** Take a `take_screenshot` so the user can verify in the chat history.
8. **Return home.** `global_action(action = "home")`.

## Tools used

- `list_recent_notifications`, `list_active_notifications`
- `launch_app`, `notification_action_click`
- `read_window_tree`, `find_node`, `click_node`, `set_text`, `scroll`
- `take_screenshot`
- `global_action`

## Failure modes

- **Cannot find input field.** Some apps render send-message in a dialog overlay. Try `read_window_tree` again after a brief delay; if still no luck, abort and tell the user "I opened the chat but couldn't locate the input field — please reply manually".
- **Send button is greyed out.** The draft probably failed to set. Try `set_text` once more; if still greyed, abort.
- **Wrong contact opened.** If `find_node` matched a different person than expected (common with similar names), back out with `global_action(action = "back")` and try the search field instead.
- **Notification was already dismissed.** `list_recent_notifications` is a 100-entry ring buffer — older entries stay visible even after dismissal. Always re-check by opening the app rather than trusting that the chat is the topmost one.

## Don't

- Don't reply on behalf of anyone in a group chat without explicit confirmation — too easy to embarrass.
- Don't send anything that contains the user's real personal info (full name, phone, address) unless the user typed it themselves.
- Don't summarise the conversation back to the user before sending unless they asked you to confirm the draft first.
