# Assistant-specific chat input placeholder design

## Goal

Allow each Android assistant to control the placeholder shown in the chat message input while preserving the current localized placeholder as the default behavior.

## Scope

- Android app only.
- Add the controls to **Assistant settings → Basic settings**.
- Apply the result to both the normal chat input and the full-screen editor.
- Preserve the existing Web UI behavior for a separate follow-up.

## User-visible behavior

The assistant has three persisted settings:

1. Whether custom chat input placeholder behavior is enabled.
2. Whether the placeholder is intentionally empty.
3. The custom placeholder text.

The effective placeholder is resolved as follows:

| Custom behavior | Allow empty | Custom text | Effective placeholder |
|---|---|---|---|
| Off | Either | Any | Existing localized `chat_input_placeholder` resource |
| On | On | Any | No placeholder |
| On | Off | Non-blank | Custom text |
| On | Off | Empty or whitespace-only | Existing localized `chat_input_placeholder` resource |

Disabling custom behavior or enabling the empty-placeholder option must not erase the saved custom text. This allows users to restore their previous text by changing the switches again.

## Data model and persistence

Extend the existing serializable `Assistant` model with three defaulted properties:

```kotlin
val enableCustomInputPlaceholder: Boolean = false
val allowEmptyInputPlaceholder: Boolean = false
val inputPlaceholder: String = ""
```

These names are fixed for the implementation and follow the model's existing boolean and text naming conventions.

The fields use the existing `Assistant` serialization and `SettingsStore` persistence path. Default values preserve compatibility with assistant JSON written by older versions, so no explicit preference migration is required.

## Assistant basic settings UI

Add a new `FormItem` section to `AssistantBasicPage`, using the page's existing `Switch`, `OutlinedTextField`, divider, and `AssistantDetailVM.update()` patterns.

- The primary switch controls whether custom placeholder behavior is enabled.
- When the primary switch is off, no subordinate controls are shown.
- When it is on, show an **Allow empty placeholder** switch.
- When **Allow empty placeholder** is off, show a single-line custom text field.
- When **Allow empty placeholder** is on, hide the custom text field without clearing its value.
- The custom text field's own placeholder reuses `R.string.chat_input_placeholder`, so an empty setting visually previews the localized fallback.
- The text field has no additional application-level length limit.

Only labels and descriptions for the new controls require new localized string resources. Add them to every currently maintained Android strings file.

## Chat input integration

`ChatInput` already resolves the current assistant from `Settings`. Reuse that value to compute one effective placeholder according to the behavior table.

Pass the resolved value through the existing input composables so that:

- the regular `TextField` and full-screen `TextField` use the same result;
- a `null` effective value renders no placeholder composable;
- a non-null value renders the existing placeholder `Text`.

Keep resolution local to the existing chat input implementation unless extracting a small internal pure function materially improves testability. Do not introduce a repository, database table, migration, or unrelated abstraction.

## Compatibility and edge cases

- Existing assistants default to custom behavior disabled and remain visually unchanged.
- Blank and whitespace-only custom text fall back to the localized default unless empty placeholders are explicitly allowed.
- The current system locale determines the fallback text.
- Switching assistants or opening a conversation associated with another assistant updates the placeholder from that assistant's settings.
- Placeholder text remains UI-only and is never inserted into the message state or sent to an AI provider.
- Saved custom text survives switch changes that temporarily hide or bypass it.

## Verification

Add focused tests around the effective-placeholder decision if the implementation extracts a pure resolver. Cover at minimum:

1. Custom behavior disabled uses the localized fallback.
2. Custom behavior enabled with empty allowed returns no placeholder.
3. Custom behavior enabled with non-blank text returns custom text.
4. Custom behavior enabled with blank or whitespace-only text and empty disallowed uses the fallback.

Also verify:

- Kotlin compilation for the Android app.
- Relevant unit tests.
- All Android locale files contain the new resources.
- `git diff --check` succeeds.
- No Web UI files are changed.

## Development workflow

- Work in fork `c45v3/rikkahub` on a feature branch based on `rikkahub/rikkahub:master`.
- Commit the approved design before implementation.
- Implement with focused tests and run the relevant Android verification commands.
- Request an independent code review after implementation.
- Push the feature branch and create an upstream pull request only after verification succeeds.
