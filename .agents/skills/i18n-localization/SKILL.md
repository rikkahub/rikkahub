---
name: i18n-localization
description: Use this skill when users request i18n/localization updates for Android string resources, such as adding new keys, translating strings.xml across locales, fixing terminology, or cleaning up unused strings via the tools/i18n CLI.
---

# i18n Localization

Android string resources are managed with the Bun CLI in `tools/i18n` (config: `tools/i18n/config.yml`,
API key: `tools/i18n/.env`). Run commands from the repo root as `bun tools/i18n/src/cli.ts <command>`;
run `bun install --cwd tools/i18n` first if `tools/i18n/node_modules` is missing.

## Workflow

1. Confirm the target module (`app`, `ui`, `search`, `oauth`; defaults to `app`).
2. Create/update strings with the CLI instead of editing every locale file by hand.
3. Prefer auto-translation unless the user asks to skip it.
4. Verify the changes in the affected `values-*/strings.xml` files (`git diff`).
5. Report the exact files changed and what was added/updated.

## Commands

```bash
# Add (or update) an English string and translate it to every configured language
bun tools/i18n/src/cli.ts add <key> "<English Value>" [-m module] [--skip-translate] [--raw]

# Set one language by hand (defaults to the source language)
bun tools/i18n/src/cli.ts set <key> "<value>" -l values-zh [-m module] [--raw]

# Remove keys from all languages of a module
bun tools/i18n/src/cli.ts remove <key> [<key>...] [-m module]

# Inspect a module
bun tools/i18n/src/cli.ts list [-m module] [--missing] [--match <regex>]

# Batch-translate missing entries (e.g. after adding a language)
bun tools/i18n/src/cli.ts translate-missing [-l code,code] [-m module,module] [--dry-run]

# Report / delete strings not referenced by code, and translations whose key no longer exists in English
bun tools/i18n/src/cli.ts unused [-m module,module] [--delete]
```

Values passed to `add`/`set` are plain text: `&`, `<`, `>` and quotes are escaped automatically. Use `--raw` to
write XML as-is (e.g. inline markup). `translatable="false"` entries are never translated.

Batch options shared by `translate-missing` and `retranslate`: `-l/--lang` and `-m/--module` (comma-separated, default
all; repeating the flag keeps only the last value), `-c/--concurrency` (default 8), `--retries` (attempts per batch, default 3), `--dry-run`.

Translations are validated (placeholders, `\n` count and inline tags must match the source; bare quotes and `&` are
escaped). Entries that still fail after retries are not written and are listed at the end with a non-zero exit
code; rerun to fill them.

`unused` cannot see keys built dynamically; review the list before running it with `--delete`.

## Terminology (glossary)

Each language in `tools/i18n/config.yml` may define a `glossary` (English term -> target rendering) that is appended
to the translation prompt. To fix inconsistent terms:

1. Add or adjust the term in that language's `glossary` (prefer the base/indefinite form so the model can inflect).
2. Preview affected entries, then retranslate them:

```bash
bun tools/i18n/src/cli.ts retranslate -l values-ar --match '\bprompts?\b' --dry-run
bun tools/i18n/src/cli.ts retranslate -l values-ar --match '\bprompts?\b'
bun tools/i18n/src/cli.ts retranslate -l values-ar -m app --key some_key,other_key
```

`retranslate` only overwrites entries that already exist in the target language; `--match` is a case-insensitive
regex against the English source, `--key` selects comma-separated keys (union when both are given).
Run `bun tools/i18n/src/cli.ts <command> --help` for all options.

## Adding a new language

1. Add `values-xx` to `languages` in `tools/i18n/config.yml`, with a `glossary` for core AI terms (prompt, provider, context, ...).
2. Run `translate-missing -l values-xx`.
3. Build resources (`./gradlew :app:processDebugResources`) to verify; `generateLocaleConfig` picks the locale up automatically.

## Constraints

- Input values should be English.
- If the user explicitly requests localization, ensure all configured languages are updated.
- Do not commit secrets or API keys.
