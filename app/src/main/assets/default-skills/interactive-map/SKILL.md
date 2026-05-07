---
name: interactive-map
description: Show an interactive Google Maps embed for a location. Returns a webview URL the chat surface can render.
compatibility: js
auto_load: false
---

# Interactive map

## Examples

- "Show [a place] on interactive map"
- "Find [a place] on interactive map"

## Instructions

Call the `run_js` tool with the following exact parameters:

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: A JSON string with the following field
  - location: The location to show on the map.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
