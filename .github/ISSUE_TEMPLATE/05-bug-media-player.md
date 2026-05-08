---
name: 🎵 Music or media player bug
about: Report a problem with `play_media`, `pause_media`, `resume_media`, `stop_media`, lock-screen controls, or post-stop snapshot resume
title: '[Media] '
labels: bug, media
assignees: ''

---

## Which tool?

- [ ] `play_media`
- [ ] `pause_media`
- [ ] `resume_media` (live session)
- [ ] `resume_media` (snapshot fallback after force-stop)
- [ ] `stop_media`
- [ ] Lock-screen / headphone-key controls

## What happened

Did playback stutter or fail to start? Did pause / resume miss the position? Did `resume_media` lose your queue after a force-stop? Did lock-screen art not show up? Did headphone keys not control the session?

## What you expected

## Source

Was the media a:

- [ ] Local file (path on the phone, including via `~`)
- [ ] HTTPS URL (e.g. a podcast)
- [ ] Other (please describe)

If the URL or file path is shareable, paste it. Otherwise describe the format (mp3, m4a, ogg, opus, mp4, etc.).

## Lifecycle context

- [ ] App was in foreground the whole time
- [ ] App was backgrounded but not killed
- [ ] App was force-stopped between actions (this exercises the snapshot fallback)
- [ ] Phone went to sleep / screen off
- [ ] Headphones plugged or unplugged during playback

## Logs

Ask the assistant: *"generate a bug report"*. Attach the ZIP.

Logcat for media specifically: `adb logcat -d | grep -E "MediaPlaybackService|MediaPlayer" > media.log`.

## Version + device

- App version:
- Android version:
- Device:
- Bluetooth audio in use: yes / no
