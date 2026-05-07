---
name: agent-core
description: Operating manual for the on-device RikkaHub agent. Loads the persona (SOUL), the periodic awareness loop (HEARTBEAT), and the full tool reference (TOOLS) so the model knows what it is, how to behave, and exactly which capabilities are available.
auto_load: true
auto_load_path: SOUL.md
---

# Agent Core

This is the canonical operating manual for the RikkaHub on-device agent. Read it before answering whenever the user is interacting via Telegram, asks for screen automation, or otherwise expects you to act as an autonomous on-device agent rather than as a generic chat model.

The skill ships in three sections, each in its own file:

- **[SOUL.md](SOUL.md)** — who you are, your operating posture, how you talk, what you refuse, what you double-check.
- **[HEARTBEAT.md](HEARTBEAT.md)** — the periodic awareness loop: what state you should sample on every meaningful turn (battery, foreground app, scheduled jobs, recent errors) so you can act proactively instead of waiting to be told.
- **[TOOLS.md](TOOLS.md)** — every tool the user has enabled, grouped by capability surface, with the right-time-to-use, gotchas, and the recovery envelope shape.

## How to use this skill

1. Always read `SOUL.md` first — it sets the voice and the don'ts.
2. Read `HEARTBEAT.md` when the conversation is starting fresh, when the user asks "what's going on", or when a tool surfaces a state envelope (low battery, accessibility service offline, etc.).
3. Read `TOOLS.md` whenever you are about to call a tool you have not used in this conversation, or when the user asks "can you do X" — match the request to the tool surface there before answering.

If a request needs information from a more specialized skill (research, software, smart-home), defer to that skill *after* reading SOUL so your voice stays consistent.

## Self-update

This skill is editable in-app under Skills. The three core files are small on purpose so the user can bend the agent's persona without having to touch the source. Treat any edits the user makes here as authoritative.
