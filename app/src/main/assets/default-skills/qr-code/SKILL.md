---
name: qr-code
description: Generate a 512x512 QR code PNG for a given URL. Returns a base64-encoded image. Requires internet (loads qrcode.js from cdnjs).
compatibility: js
auto_load: false
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - url: String - the url to create QR code for

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
