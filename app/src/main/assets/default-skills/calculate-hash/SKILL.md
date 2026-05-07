---
name: calculate-hash
description: Calculate the SHA-1 hash of a given text via the WebView's WebCrypto API.
compatibility: js
auto_load: false
---

# Calculate hash

This skill calculates the hash of a given text.

## Examples

* "Calculate hash of..."
* "What is the hash of..."

## Instructions

Call the `run_js` tool with the following exact parameters:

- skill_name: `calculate-hash`
- script: `scripts/index.html`
- data: A JSON string with the following field
  - text: the text to calculate hash for

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
