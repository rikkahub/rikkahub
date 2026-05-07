---
name: text-spinner
description: Render a 3D-spinning text label inside a webview. Returns a relative webview URL the chat surface can embed.
compatibility: js
auto_load: false
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - label: The text string to spin on my head.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
