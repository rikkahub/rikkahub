# What this page manages

This is the shared **SillyTavern preset library** for the app.

It is the right place for:
- ST prompt order and prompt definitions
- runtime prompts such as new chat and continue behavior
- sampling values
- ST regex scripts

Assistant-specific character cards still live under each assistant.

## Quick start

1. Import a preset JSON from SillyTavern, or create a default preset.
2. Open the preset library if you have many presets.
3. Select one preset as the active shared preset.
4. Edit prompt order, runtime prompts, sampling, and regex rules.
5. Export again if you want to send it back to ST.

## Demo: manage a crowded library

```text
Goal:
Keep 20 imported presets manageable

Steps:
1. Tap Manage library
2. Search "claude" or "rp"
3. Pick the preset you want
4. Return to the main page and edit only the active preset
```

## What each area means

- **Library**: switch, search, export, and delete presets.
- **Preset editor**: prompt order, prompt definitions, runtime texts, names behavior.
- **Sampling editor**: temperature, top-p, penalties, and related generation settings.
- **Regex editor**: ST-style regex scripts shared with the active preset.

## Names behavior note

The app preserves ST compatibility here. The **Content** mode is the one fully mirrored by the current in-app runtime.

> If you imported many presets before, use the library drawer first. Do not try to manage everything from the main page.
