# Custom Chat Input Placeholder Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add assistant-specific Android controls for default or custom chat input placeholder text, preserving whitespace so users can intentionally create a blank-looking UI.

**Architecture:** Extend the existing serializable `Assistant` model and reuse `SettingsStore`/`AssistantDetailVM.update()` for persistence. A small pure helper returns a non-null effective placeholder string. The existing `ChatInput` tree passes that string to both normal and full-screen editors. Build settings from existing `FormItem`, `Switch`, and `OutlinedTextField` patterns.

**Verification policy:** Do not run Gradle, Android compilation, or JVM tests on the local server. Use only static local checks; GitHub Actions supplies compilation and test evidence.

---

### Task 1: Add the model and non-null resolver

**Objective:** Persist the feature switch and custom text, and define exact empty-versus-whitespace semantics.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/model/AssistantInputPlaceholderTest.kt`

**Step 1: Add focused tests**

Create four JUnit tests:

```kotlin
package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantInputPlaceholderTest {
    private val fallback = "Input a message to chat with AI"

    @Test
    fun `custom behavior disabled uses fallback`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = false,
            inputPlaceholder = "Custom",
        )

        assertEquals(fallback, assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `non empty custom text is used`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "Ask me anything",
        )

        assertEquals("Ask me anything", assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `empty custom text uses fallback`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "",
        )

        assertEquals(fallback, assistant.resolveInputPlaceholder(fallback))
    }

    @Test
    fun `whitespace custom text is preserved`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "   ",
        )

        assertEquals("   ", assistant.resolveInputPlaceholder(fallback))
    }
}
```

Do not run the test locally. The expected RED condition is statically evident: the model fields and resolver do not yet exist. GitHub Actions will execute the tests after implementation is pushed.

**Step 2: Add the minimal model implementation**

Add defaulted fields to `Assistant`:

```kotlin
val enableCustomInputPlaceholder: Boolean = false,
val inputPlaceholder: String = "",
```

Add the non-null helper after the data class:

```kotlin
fun Assistant.resolveInputPlaceholder(defaultPlaceholder: String): String {
    if (!enableCustomInputPlaceholder) return defaultPlaceholder
    return inputPlaceholder.ifEmpty { defaultPlaceholder }
}
```

`ifEmpty` is required. Do not use `ifBlank`, `trim`, or nullable return values.

**Step 3: Perform static checks and commit**

Inspect the diff, run `git diff --check`, and confirm only the model and test changed. Do not invoke Gradle.

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt \
  app/src/test/java/me/rerere/rikkahub/data/model/AssistantInputPlaceholderTest.kt
git commit -m "feat: add assistant input placeholder settings"
```

---

### Task 2: Add expandable basic-settings controls

**Objective:** Reuse existing settings controls for one primary switch and one subordinate single-line editor.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt`
- Modify: all six `app/src/main/res/values*/strings.xml` locale files

**Step 1: Add four localized resource keys**

Add natural translations of:

```xml
<string name="assistant_page_custom_input_placeholder">Custom input placeholder</string>
<string name="assistant_page_custom_input_placeholder_desc">Customize the placeholder shown in this assistant’s message input</string>
<string name="assistant_page_input_placeholder">Input placeholder</string>
<string name="assistant_page_input_placeholder_desc">Leave empty to use the localized default; spaces are preserved</string>
```

Add each key exactly once to:

- `values/strings.xml`
- `values-zh/strings.xml`
- `values-zh-rTW/strings.xml`
- `values-ja/strings.xml`
- `values-ko-rKR/strings.xml`
- `values-ru/strings.xml`

**Step 2: Add the setting UI**

In the first basic-settings card, add a divider and parent `FormItem`:

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
```

Do not trim, clear, reject, or otherwise normalize the field value.

**Step 3: Perform static checks and commit**

Use Python XML parsing or equivalent non-compiling checks to ensure all four keys exist once in each locale. Run `git diff --check`. Do not invoke Gradle.

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt \
  app/src/main/res/values*/strings.xml
git commit -m "feat: add input placeholder assistant controls"
```

---

### Task 3: Apply the non-null placeholder to both editors

**Objective:** Resolve once and reuse the same valid `String` in the normal and full-screen chat inputs.

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`

**Step 1: Resolve once**

Import `resolveInputPlaceholder`. Near the existing current assistant value:

```kotlin
val defaultInputPlaceholder = stringResource(R.string.chat_input_placeholder)
val inputPlaceholder = assistant.resolveInputPlaceholder(defaultInputPlaceholder)
```

**Step 2: Pass a non-null `String` through existing private composables**

Add `inputPlaceholder: String` to `TextInputRow` and `FullScreenEditor`. Pass the same value from `ChatInput` through `TextInputRow` into `FullScreenEditor`.

**Step 3: Render the same string in both text fields**

Replace both hard-coded placeholder resource usages with:

```kotlin
placeholder = {
    Text(inputPlaceholder)
},
```

Do not modify `ChatInputState` and do not make the parameter nullable.

**Step 4: Perform static checks and commit**

Inspect both call sites and function signatures, run `git diff --check`, and ensure no Web UI file changed. Do not invoke Gradle.

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
git commit -m "feat: apply assistant chat input placeholder"
```

---

### Task 4: GitHub Actions verification, review, and PR

**Objective:** Obtain real remote test/build evidence without compiling locally, then open the upstream contribution.

**Step 1: Add fork feature-branch CI**

Upstream currently contains only scheduled/manual Daily Build and no pull-request workflow. Add a fork-oriented workflow that triggers on pushes to `feat/custom-chat-input-placeholder` and:

1. Checks out the branch.
2. Sets up JDK 17.
3. Sets up pnpm 11 and Node 22.
4. Installs `web-ui` dependencies with `pnpm install --frozen-lockfile`.
5. Sets up the Android SDK needed by the repository.
6. Writes a valid non-secret debug-only `app/google-services.json` matching `me.rerere.rikkahub.debug` or otherwise supplies the Firebase config required for debug compilation without repository secrets.
7. Runs:

```bash
./gradlew :app:testDebugUnitTest \
  --tests me.rerere.rikkahub.data.model.AssistantInputPlaceholderTest
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The workflow must not publish artifacts/releases, use signing secrets, or run on upstream schedules.

**Step 2: Push and inspect the actual Actions run**

Push the feature branch to `origin`. Inspect the run through GitHub's API. If it fails, read logs, fix the source or workflow, push, and wait for a passing run. Never substitute local compilation.

**Step 3: Run local non-compiling verification**

- Parse all six locale XML files and verify four new keys exactly once.
- Run `git diff --check upstream/master...HEAD`.
- Inspect `git diff --name-only upstream/master...HEAD` and confirm no `web-ui/` or `web/` source file changed.
- Confirm all placeholder parameters and resolver returns are non-null `String`.

**Step 4: Independent reviews**

Request specification compliance review, then code quality review, against:

- `docs/superpowers/specs/2026-07-14-custom-chat-input-placeholder-design.md`
- This implementation plan
- Base `upstream/master`
- Current HEAD

Fix all Critical and Important findings. Any source change requires another GitHub Actions run.

**Step 5: Create upstream PR**

Create a pull request from `c45v3:feat/custom-chat-input-placeholder` to `rikkahub/rikkahub:master`. Include:

- Behavior summary.
- The passing GitHub Actions run URL.
- Exact test/build commands run remotely.
- Explicit statement that no local compilation was performed.

Do not manually dispatch unrelated workflows or merge the PR.
