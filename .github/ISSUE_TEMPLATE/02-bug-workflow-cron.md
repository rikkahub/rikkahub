---
name: ⚙️ Workflow or scheduled-job bug
about: Report a problem with workflows (Tasker-style) or scheduled jobs (cron / one-shot timers)
title: '[Workflow/Cron] '
labels: bug, workflows, cron
assignees: ''

---

## Workflow or scheduled job?

- [ ] Workflow (event-driven: WiFi, Bluetooth, headphones, geofence, app launch, notification, screen, etc.)
- [ ] Scheduled job (cron expression, `@every`, or one-shot at a timestamp)

## What happened

Did a trigger fail to fire? Did a condition not gate as expected? Did a scheduled job run twice, or skip, or hang? Did the LLM mode pick the wrong assistant? Did the direct mode fail a specific tool call?

## What you expected

What should have happened instead, and when?

## The workflow / job definition

Paste the JSON output of `workflow_get` or `list_jobs` for the affected workflow / job. Or describe what you asked the AI to set up.

## When it fired (or didn't)

If the run history is available, paste a snippet from `get_job_history` or the Settings → Workflows / Scheduled jobs detail page (which shows run history at the bottom). The `outcome` and `error_message` columns are especially useful.

## Triggers and conditions involved

If a workflow: which trigger type? Which conditions? Were the relevant Android permissions granted (location, notifications, accessibility, Bluetooth)?

## Logs

Ask the assistant: *"generate a bug report"*. Attach the resulting redacted ZIP.

For scheduler-specific issues, also useful: open Settings → Doctor → Background services and paste the report.

## Version + device

- App version: (e.g. `v2.1.16-agent.0`)
- Android version:
- Device:
- OEM aggressive task killer (Xiaomi, Samsung One UI, Honor, Vivo): yes / no / unknown
