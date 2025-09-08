# Repository Guidelines

## Project Structure & Modules
- `app/`: Android app (Jetpack Compose UI, DI, data, Room, navigation).
- `ai/`: AI SDK and provider integrations (OpenAI, Google, Anthropic) with optional native/NN deps.
- `highlight/`, `search/`, `tts/`, `common/`: Feature and utility modules.
- Tests: `ai/src/test` (unit), `ai/src/androidTest` (instrumented); `app/src/test` scaffolded.
- Assets: `app/src/main/assets`, resources under `app/src/main/res`.

## Coding Style & Naming
- Kotlin with 4‑space indent, 120 char line limit (`.editorconfig`).
- Classes/objects: PascalCase; functions/properties: camelCase; resources: snake_case.
- Compose: Composables start UpperCamelCase; pages typically end with `Page` (e.g., `SettingProviderPage`).
- Keep modules isolated; share utilities via `common`.

## Testing Guidelines
- Frameworks: JUnit (unit), AndroidX test/espresso (instrumented).
- Place unit tests alongside module under `src/test/...`; instrumented under `src/androidTest/...`.
- Name tests `*Test.kt` and cover parsing, providers, and critical transforms.
- Validate instrumented flows for streaming/SSE where feasible.

## Commit & PR Guidelines
- Use Conventional Commits: `feat:`, `fix:`, `chore:`, `refactor:` … with a clear, concise subject. Scope optional.
- Keep PRs focused; include description, linked issue, and screenshots for UI changes.
- Run `test` and `lint` before opening PRs; note any platform caveats (device/emulator).
- Per README: do not submit new languages, unrelated large features, or broad refactors/AI‑generated mass edits.

## Security & Configuration
- Never commit secrets or signing files. Keep API keys in secure storage; avoid hardcoding.
- `local.properties` holds signing values; `google-services.json` stays in `app/` and is ignored by Git.
