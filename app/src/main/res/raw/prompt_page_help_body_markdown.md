# Prompt systems in this app

This page separates the prompt features so they stay manageable.

## When to use each one

- **SillyTavern Preset**: shared ST prompt order, runtime prompts, sampling, and ST regex scripts.
- **Mode Injection**: a manual prompt block you turn on because you want a mode, such as translation mode or coding mode.
- **Lorebook**: keyword-triggered world info that appears only when matching context is found.

## Quick decision guide

```text
I want the whole app to use one ST preset library
-> SillyTavern Preset

I want a reusable manual mode like "Explain everything simply"
-> Mode Injection

I want facts or lore to appear only when certain words show up
-> Lorebook
```

## Demo: three similar requests, three different tools

### Case A
"Always use my imported ST prompt order and continue behavior"

Use **SillyTavern Preset**.

### Case B
"When I switch to study mode, prepend a tutoring instruction"

Use **Mode Injection**.

### Case C
"When chat mentions the royal family, inject the kingdom setting"

Use **Lorebook**.

> If the rule should trigger automatically from context, it is probably a lorebook entry. If you want to pick it manually, it is probably a mode injection.
