---
name: 📁 File manager bug
about: Report a problem with the AI file manager tools (list / find / read / write / move / copy / delete) or workspace tilde (`~`)
title: '[File Manager] '
labels: bug, file-manager
assignees: ''

---

## Which tool?

- [ ] `list_files`
- [ ] `find_files`
- [ ] `read_file`
- [ ] `file_info`
- [ ] `write_text_file`
- [ ] `write_binary_file`
- [ ] `copy_file`
- [ ] `move_file`
- [ ] `delete_file`
- [ ] `create_directory`
- [ ] `show_image`
- [ ] `open_file`
- [ ] tilde (`~`) workspace path expansion

## What happened

Did a tool return empty when files exist? Did it permission-error a path you expected to work? Did `~/...` not resolve to the workspace? Did write fail silently? Did the path-safety guard reject something that should be allowed?

## What you expected

## Path involved

The path argument(s) you passed (or what the AI said it passed). For sensitivity, you can replace personal directory names with placeholders (e.g. `/sdcard/Documents/<my-project>/foo.txt`).

## Permissions state

- [ ] `MANAGE_EXTERNAL_STORAGE` (All Files Access) granted
- [ ] App-private path (e.g. `~/...` or `/data/data/me.rerere.rikkahub/...`)
- [ ] Other shared-storage path (e.g. `/sdcard/Download/`)

Check Settings → Doctor → Permissions for the current grants if you're not sure.

## Logs

Ask the assistant: *"generate a bug report"*. Attach the ZIP. If the failure is reproducible, the most useful thing is the JSON response envelope from the failing tool call (paste it from chat).

## Version + device

- App version:
- Android version: (especially API 34+ / Android 15+ since scoped storage tightened)
- Device:
