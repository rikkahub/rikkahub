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
```

```bash
# Batch-translate all missing entries (e.g. after adding a new language to config.yml)
uv run --directory locale-tui src/main.py translate-missing [OPTIONS]

# Examples
uv run --directory locale-tui src/main.py translate-missing --dry-run      # only report missing counts
uv run --directory locale-tui src/main.py translate-missing -l values-ar   # one language, all modules
uv run --directory locale-tui src/main.py translate-missing -m app -l values-ja -l values-ru
```

## Options

- `--module, -m`: Specify module name (defaults to first module in config)
- `--skip-translate`: Add only to source language and skip translations

`translate-missing` options:

- `--lang, -l`: Target language code, repeatable (defaults to all non-source languages)
- `--module, -m`: Module name, repeatable (defaults to all modules)
- `--concurrency, -c` / `--retries`: Parallel requests (default 8) / attempts per batch (default 3)
- `--dry-run`: Only print missing counts per module/language

Translations are validated (placeholders and `\n` must match the source, quotes are escaped). Entries that
still fail after retries are not written and are listed at the end with a non-zero exit code; rerun to fill them.

## Adding a new language

1. Add `values-xx` to `languages` in `locale-tui/config.yml`.
2. Run `translate-missing -l values-xx`.
3. Build resources (`./gradlew :app:processDebugResources`) to verify; `generateLocaleConfig` picks the locale up automatically.

## Constraints

- Input value should be English.
- If user explicitly requests localization, ensure all configured languages are updated.
- Do not commit secrets or API keys.
