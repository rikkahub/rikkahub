# What a mode injection is

A mode injection is a manual prompt block.

It is **not** keyword-triggered like a lorebook entry, and it is **not** the full ST preset structure.

Use it when you want a reusable mode such as:
- study mode
- translation mode
- code review mode
- writing polish mode

## Quick start

1. Create one injection.
2. Give it a name that describes the mode.
3. Choose a position near the system prompt.
4. Put the actual instruction into the content.
5. Use priority to control order if multiple injections can be active together.

## Demo

```text
Name:
Explain Simply

Position:
After system prompt

Priority:
100

Content:
Explain concepts in plain language, avoid jargon, and use one short example when helpful.
```

## How to think about position

- **Before system prompt**: strongest framing, usually for high-level behavior.
- **After system prompt**: safer default for most extra instructions.

> Start with **After system prompt** unless you know you need a stronger override.
