---
name: virtual-piano
description: Show a playable 88-key virtual piano with sampled note audio inside a webview.
compatibility: js
auto_load: false
---

# Virtual Piano

A playable, horizontally-scrolling virtual piano keyboard that uses web audio.

## Files
- `scripts/index.html`: The local entry point that loads `scripts/index.js`.
- `scripts/index.js`: Returns the webview URL `ui.html?v=<timestamp>` pointing to the local UI under `assets/`.
- `assets/ui.html`: The piano keyboard UI (3D, 88 keys, horizontally scrolling).
- `assets/assets/<n>.mp3`: Sampled audio for each of the 88 piano keys.

## Prompts / Triggers
- "Open virtual piano"
- "Play the piano"
- "I want to play piano"
- "Show me a piano keyboard"

## Instructions

Call the `run_js` tool with:
- skill_name: `virtual-piano`
- script: `scripts/index.html`
- data: A JSON string (any payload, the skill ignores it).

The skill returns a `webview` result with a relative URL (`ui.html?v=...`). The chat surface decides how to render embedded webview returns; if it cannot inline the iframe it falls back to a clickable link.

Homepage: <https://github.com/google-ai-edge/gallery/tree/main/skills/featured/virtual-piano>

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
