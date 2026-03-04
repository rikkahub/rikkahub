---
name: locale-tui-localization
description: Use this skill when users request i18n/localization updates for Android string resources, especially when adding new keys or translating via locale-tui.
---

# Locale TUI Localization

Use this skill for Android localization tasks that should be handled by `locale-tui`.

## When to use

- The user asks to add a new localized string key.
- The user asks to translate/update `strings.xml` across multiple locales.
- The user mentions i18n/l10n or `locale-tui`.

## Workflow

1. Confirm the target module (for example `app`).
2. Create/update strings with `locale-tui` instead of editing all locale files by hand.
3. Prefer auto-translation unless the user asks to skip it.
4. Verify generated changes in affected `values-*/strings.xml` files.
5. Report the exact files changed and what was added/updated.

## Commands

```bash
# Add a new string resource with automatic translation
uv run --directory locale-tui src/main.py add <key> "<English Value>" [OPTIONS]

# Examples
uv run --directory locale-tui src/main.py add hello_world "Hello, World!"
uv run --directory locale-tui src/main.py add greeting "Welcome" -m app
uv run --directory locale-tui src/main.py add test_key "Test" --skip-translate

# Manually set a string value for a specific language
uv run --directory locale-tui src/main.py set <key> "<Value>" [OPTIONS]

# Examples
uv run --directory locale-tui src/main.py set hello_world "你好，世界！" -l values-zh
uv run --directory locale-tui src/main.py set greeting "Welcome" -l values
uv run --directory locale-tui src/main.py set test_key "テスト" -l values-ja -m app

# List all string resource keys
uv run --directory locale-tui src/main.py list-keys [-m module]
```

## Options

### add
- `--module, -m`: Specify module name (defaults to first module in config)
- `--skip-translate`: Add only to source language and skip translations

### set

- `--lang, -l`: Specify language code (e.g., values, values-zh, values-ja), defaults to source language (values)
- `--module, -m`: Specify module name (defaults to first module in config)

## Constraints

- Input value should be English.
- If user explicitly requests localization, ensure all configured languages are updated.
- Do not commit secrets or API keys.
