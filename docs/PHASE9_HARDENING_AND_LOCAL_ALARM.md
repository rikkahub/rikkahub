# Phase 9 (adjusted): Hardening + Local Alarm Tools

## Scope (user decision)

- **No** cloud Todo / alarm domain sync.
- Local **Alarm** tools only (device-side Tool Calls).
- Keep cloud reliability fixes already landed (providers seed, etc.).

## Local alarm tools

| Tool | Action | Permission |
|------|--------|------------|
| `alarm_query` | Next system alarm via `AlarmManager.getNextAlarmClock()` | None |
| `alarm_create` | `AlarmClock.ACTION_SET_ALARM` → system Clock UI | None (user confirms in Clock) |
| `alarm_show` | `AlarmClock.ACTION_SHOW_ALARMS` | None |

### Why not AlarmManager exact alarms?

Android 12+ restricts `SCHEDULE_EXACT_ALARM` / silent scheduling. For “set a clock alarm” UX, Play policy prefers the system Clock intent. Full alarm lists are **not** a public API.

### Enable path

1. Assistant → Local tools → **Alarms** switch.
2. Requires a resolvable Clock app (`canUseAlarmTools`).
3. Create/show tools still use **chat HITL approval** (`needsApproval = true`).
4. Preference may sync with the assistant; runtime exposure is **local capability gated** (same bounce-safe pattern as Calendar/ScreenTime).

## Hardening checklist (ongoing)

- [x] Providers cloud sync + missing-key seed after upgrade
- [x] Perry credential rebind per device
- [x] Local alarm tools (no cloud)
- [ ] Weak-network / reinstall smoke for settings, conversations, files, providers
- [ ] Backup ZIP vs online sync boundary doc for users
- [ ] WSL ops: Perry + Monel loopback + MinIO env checklist

## Ops quick notes

- Perry: WSL `/home/purbliss/src/haruhome/server`, rsync from Windows `rikkahub/server` **excluding** `.env`.
- Monel: `127.0.0.1:7890`, `MONEL_AUTH_KEY` server-only.
- Android never holds MinIO keys or Monel auth key.
