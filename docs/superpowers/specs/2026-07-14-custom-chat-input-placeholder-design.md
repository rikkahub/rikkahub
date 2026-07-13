# Assistant-specific chat input placeholder design

## Goal

Allow each Android assistant to customize the placeholder shown in the chat message input while preserving the existing localized placeholder by default.

## Scope

- Android app only.
- Controls live in **Assistant settings → Basic settings**.
- The regular chat input and full-screen editor share the same behavior.
- Web UI remains unchanged.
- No Android compilation or Gradle tests run on the local server; GitHub Actions provides build and test evidence.

## Persisted settings

Extend the existing serializable `Assistant` model with two defaulted properties:

```kotlin
val enableCustomInputPlaceholder: Boolean = false
val inputPlaceholder: String = ""
```

These fields reuse the existing `Assistant` serialization and `SettingsStore` persistence path. Their defaults preserve compatibility with older assistant JSON, so no migration is required.

## Effective placeholder behavior

| Custom behavior | Saved custom text | Effective placeholder |
|---|---|---|
| Off | Any value | Existing localized `chat_input_placeholder` resource |
| On | Empty string | Existing localized `chat_input_placeholder` resource |
| On | Any non-empty string, including whitespace-only text | Saved text exactly as entered |

The resolver always returns a non-null `String`. It uses empty-string detection rather than blank-string detection, so spaces are not trimmed, rejected, or replaced. A knowledgeable user may enter one or more spaces to make the UI appear to have no placeholder without introducing nullable state.

Disabling custom behavior hides the editor but does not erase the saved text.

## Assistant basic settings UI

Add a `FormItem` to `AssistantBasicPage`, reusing the existing `Switch`, `OutlinedTextField`, dividers, and `AssistantDetailVM.update()` flow.

- The primary switch enables custom placeholder behavior.
- When off, the subordinate text field is hidden.
- When on, a single-line text field is shown.
- The field has no additional application-level length limit.
- The field's own placeholder reuses `R.string.chat_input_placeholder`, previewing the localized fallback while its saved value is empty.
- Input is persisted exactly as entered, including whitespace.

Only the primary setting and text-field labels/descriptions require new localized resources. Add them to every currently maintained Android strings file.

## Chat input integration

`ChatInput` already resolves the current assistant from `Settings`. Resolve one effective non-null placeholder string there and pass it through the existing private composables.

Both the regular `TextField` and the full-screen `TextField` render:

```kotlin
placeholder = {
    Text(inputPlaceholder)
}
```

Do not modify `ChatInputState`; placeholder text remains UI-only and is never sent to an AI provider.

## Compatibility and edge cases

- Existing assistants keep the current localized placeholder because the new switch defaults to off.
- An enabled but empty custom field falls back to the localized default.
- Whitespace-only text is preserved verbatim and produces intentionally blank-looking UI.
- Switching assistant or conversation updates the placeholder from the active assistant.
- Saved custom text survives disabling and re-enabling the feature.
- No null placeholder value enters the Compose call chain.

## Verification

Add focused JVM tests for the pure resolver:

1. Disabled custom behavior uses the fallback regardless of saved text.
2. Enabled behavior with normal text returns the custom text.
3. Enabled behavior with an empty string uses the fallback.
4. Enabled behavior with whitespace-only text preserves the whitespace exactly.

All Gradle tests and Android compilation run in GitHub Actions, not locally. Local verification is limited to non-compiling checks such as file inspection, XML parsing, `git diff --check`, and independent review.

GitHub Actions must verify:

- Focused resolver tests.
- App JVM unit tests.
- Debug APK assembly.

Also verify that every Android locale contains the new resources and no Web UI file changes.

## Development workflow

- Work in fork `c45v3/rikkahub` on `feat/custom-chat-input-placeholder`, based on `rikkahub/rikkahub:master`.
- Use a feature-branch GitHub Actions workflow for CI because upstream currently has no pull-request build workflow.
- Do not publish releases or manually dispatch unrelated upstream workflows.
- Request independent specification and code-quality reviews.
- Create an upstream pull request only after GitHub Actions passes.
