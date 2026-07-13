# Custom Chat Input Placeholder Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add assistant-specific Android controls for default, custom, or intentionally empty chat input placeholder text.

**Architecture:** Extend the existing serializable `Assistant` model and reuse `SettingsStore`/`AssistantDetailVM.update()` for persistence. Put the placeholder decision in a small pure model helper so JVM tests can cover every state, then consume that helper from the existing `ChatInput` tree for both normal and full-screen editors. Build the settings UI from the existing `FormItem`, `Switch`, and `OutlinedTextField` patterns.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose Material 3, Android string resources, JUnit 4, Gradle.

---

### Task 1: Define and test effective placeholder behavior

**Objective:** Persist the three assistant settings and expose one pure resolver that implements the approved state table.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt:15-51`
- Create: `app/src/test/java/me/rerere/rikkahub/data/model/AssistantInputPlaceholderTest.kt`

**Step 1: Write the failing resolver tests**

Create the test class with four focused cases:

```kotlin
package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantInputPlaceholderTest {
    private val fallback = "Input a message to chat with AI"

    @Test
    fun `custom behavior disabled uses fallback`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = false,
            allowEmptyInputPlaceholder = true,
            inputPlaceholder = "Custom",
        )

        assertEquals(fallback, assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `allow empty returns no placeholder`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            allowEmptyInputPlaceholder = true,
            inputPlaceholder = "Custom",
        )

        assertNull(assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `non blank custom text is used`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "Ask me anything",
        )

        assertEquals("Ask me anything", assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `blank custom text uses fallback when empty is disallowed`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            allowEmptyInputPlaceholder = false,
            inputPlaceholder = "   ",
        )

        assertEquals(fallback, assistant.resolveInputPlaceholder(fallback))
    }
}
```

**Step 2: Run the focused test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests me.rerere.rikkahub.data.model.AssistantInputPlaceholderTest
```

Expected: compilation fails because the three fields and `resolveInputPlaceholder` do not exist.

**Step 3: Add the minimal model implementation**

Add these properties near the other assistant presentation settings:

```kotlin
val enableCustomInputPlaceholder: Boolean = false,
val allowEmptyInputPlaceholder: Boolean = false,
val inputPlaceholder: String = "",
```

Add the pure resolver after the `Assistant` declaration:

```kotlin
fun Assistant.resolveInputPlaceholder(defaultPlaceholder: String): String? {
    if (!enableCustomInputPlaceholder) return defaultPlaceholder
    if (allowEmptyInputPlaceholder) return null
    return inputPlaceholder.takeIf { it.isNotBlank() } ?: defaultPlaceholder
}
```

Default values are required so older serialized assistant JSON remains readable without a migration.

**Step 4: Run the focused test to verify pass**

Run the same Gradle command.

Expected: `AssistantInputPlaceholderTest` passes all four tests.

**Step 5: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt \
  app/src/test/java/me/rerere/rikkahub/data/model/AssistantInputPlaceholderTest.kt
git commit -m "feat: add assistant input placeholder settings"
```

---

### Task 2: Add the expandable assistant basic settings controls

**Objective:** Let users select custom placeholder behavior, intentionally empty behavior, and custom text using existing settings components.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt:141-233`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`
- Modify: `app/src/main/res/values-ko-rKR/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Step 1: Add localized resource keys**

Add page-prefixed keys to every maintained Android locale:

```xml
<string name="assistant_page_custom_input_placeholder">Custom input placeholder</string>
<string name="assistant_page_custom_input_placeholder_desc">Customize the placeholder shown in this assistant’s message input</string>
<string name="assistant_page_allow_empty_input_placeholder">Allow empty placeholder</string>
<string name="assistant_page_allow_empty_input_placeholder_desc">Show no placeholder in the message input</string>
<string name="assistant_page_input_placeholder">Input placeholder</string>
<string name="assistant_page_input_placeholder_desc">Leave blank to use the localized default</string>
```

Use natural translations in each locale rather than copying English. Preserve the existing resource ordering convention around other `assistant_page_*` strings.

**Step 2: Verify resource completeness before UI wiring**

Run a short script or search that confirms each of the six files contains all six new resource names.

Expected: every name occurs exactly once in every locale file.

**Step 3: Add the primary switch and subordinate controls**

In the first basic-settings card, add a divider and a `FormItem` following the existing switch patterns:

```kotlin
HorizontalDivider()

FormItem(
    modifier = Modifier.padding(8.dp),
    label = {
        Text(stringResource(R.string.assistant_page_custom_input_placeholder))
    },
    description = {
        Text(stringResource(R.string.assistant_page_custom_input_placeholder_desc))
    },
    tail = {
        Switch(
            checked = assistant.enableCustomInputPlaceholder,
            onCheckedChange = { enabled ->
                onUpdate(assistant.copy(enableCustomInputPlaceholder = enabled))
            },
        )
    },
) {
    if (assistant.enableCustomInputPlaceholder) {
        FormItem(
            label = {
                Text(stringResource(R.string.assistant_page_allow_empty_input_placeholder))
            },
            description = {
                Text(stringResource(R.string.assistant_page_allow_empty_input_placeholder_desc))
            },
            tail = {
                Switch(
                    checked = assistant.allowEmptyInputPlaceholder,
                    onCheckedChange = { allowEmpty ->
                        onUpdate(assistant.copy(allowEmptyInputPlaceholder = allowEmpty))
                    },
                )
            },
        )

        if (!assistant.allowEmptyInputPlaceholder) {
            OutlinedTextField(
                value = assistant.inputPlaceholder,
                onValueChange = { placeholder ->
                    onUpdate(assistant.copy(inputPlaceholder = placeholder))
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.assistant_page_input_placeholder))
                },
                placeholder = {
                    Text(stringResource(R.string.chat_input_placeholder))
                },
                supportingText = {
                    Text(stringResource(R.string.assistant_page_input_placeholder_desc))
                },
                singleLine = true,
            )
        }
    }
}
```

Keep subordinate controls in the parent `FormItem` content so they visually expand and collapse with the primary switch. Do not clear `inputPlaceholder` in either switch callback.

**Step 4: Compile the Android Kotlin/resources**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If the repository's web pre-build blocks because `pnpm` is absent, install/enable the repository-required pnpm toolchain rather than bypassing the build graph.

**Step 5: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt \
  app/src/main/res/values*/strings.xml
git commit -m "feat: add input placeholder assistant controls"
```

---

### Task 3: Reuse the effective placeholder in both chat editors

**Objective:** Make the regular and full-screen Android chat inputs render the current assistant's effective placeholder without inserting it into message state.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:117-134, 235-239, 418-423, 546-558, 590-593, 727-775`

**Step 1: Resolve the placeholder once in `ChatInput`**

Import the model helper and resolve against the existing localized resource:

```kotlin
import me.rerere.rikkahub.data.model.resolveInputPlaceholder
```

Near the existing `assistant` value:

```kotlin
val defaultInputPlaceholder = stringResource(R.string.chat_input_placeholder)
val inputPlaceholder = assistant.resolveInputPlaceholder(defaultInputPlaceholder)
```

**Step 2: Thread the nullable value through existing composables**

Pass `inputPlaceholder` into `TextInputRow`, and from there into `FullScreenEditor`:

```kotlin
TextInputRow(
    state = state,
    completionProviders = completionProviders,
    inputPlaceholder = inputPlaceholder,
    onSendMessage = { sendMessage() },
)
```

Update private function signatures accordingly:

```kotlin
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    inputPlaceholder: String?,
    onSendMessage: () -> Unit,
)
```

```kotlin
private fun FullScreenEditor(
    state: ChatInputState,
    inputPlaceholder: String?,
    onDone: () -> Unit,
)
```

**Step 3: Reuse the nullable value in both `TextField`s**

Replace each hard-coded placeholder with:

```kotlin
placeholder = inputPlaceholder?.let { placeholder ->
    {
        Text(placeholder)
    }
},
```

Call the full-screen editor with the same value:

```kotlin
FullScreenEditor(
    state = state,
    inputPlaceholder = inputPlaceholder,
) {
    isFullScreen = false
}
```

Do not modify `ChatInputState`; the placeholder remains UI-only.

**Step 4: Run focused and compile verification**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests me.rerere.rikkahub.data.model.AssistantInputPlaceholderTest
./gradlew :app:compileDebugKotlin
```

Expected: tests and compilation succeed.

**Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
git commit -m "feat: apply assistant chat input placeholder"
```

---

### Task 4: Run repository verification and inspect scope

**Objective:** Prove the feature is correct, localized, buildable, and limited to Android plus development documentation.

**Files:**
- Verify all files changed since `upstream/master`

**Step 1: Run the relevant JVM test suite**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with all app JVM tests passing.

**Step 2: Build the Debug APK**

Ensure `app/google-services.json` and `pnpm` are available as required by `AGENTS.md`, then run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and a Debug APK under `app/build/outputs/apk/debug/`.

**Step 3: Validate localization resources**

Run a deterministic script that parses all six `strings.xml` files and verifies the six new resource keys exist once per file.

Expected: script exits 0 and reports all locale files complete.

**Step 4: Inspect diff hygiene and scope**

Run:

```bash
git diff --check upstream/master...HEAD
git status --short
git diff --name-only upstream/master...HEAD
```

Expected:

- `git diff --check` has no output.
- Worktree is clean.
- No file under `web-ui/` or `web/` is changed.
- Diff contains only the design/plan docs, assistant model/test, Android basic settings, locale resources, and `ChatInput.kt`.

**Step 5: Perform independent code review**

Dispatch a reviewer with:

- Requirements: `docs/superpowers/specs/2026-07-14-custom-chat-input-placeholder-design.md`
- Plan: `docs/superpowers/plans/2026-07-14-custom-chat-input-placeholder.md`
- Base: `upstream/master`
- Head: current branch HEAD

Fix all Critical and Important findings, rerun the focused tests/build, and commit any review fixes.

**Step 6: Push and create an upstream pull request**

After all verification succeeds:

```bash
git push -u origin feat/custom-chat-input-placeholder
```

Create a PR from `c45v3:feat/custom-chat-input-placeholder` to `rikkahub/rikkahub:master` with a concise summary and the exact test/build commands run. Do not trigger or rerun GitHub Actions manually.
