# Agent Run Detail Navigation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Run Center detail loading and parent/child navigation identity-safe, reversible, and visually continuous.

**Architecture:** Keep a UI-only immutable navigation path in `AgentRunVM`; root entry points replace the path, child links
append to it, and back removes exactly one entry. Bind loading, missing, and content states to the requested Run ID. The
bottom sheet renders these states with keyed `AnimatedContent`, so telemetry updates do not replay page transitions.

**Tech Stack:** Kotlin, StateFlow, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define an immutable navigation contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVM.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunNavigationTest.kt`

**Step 1: Write the failing test**

Verify that opening a root replaces history, opening a child appends once, revisiting an ancestor truncates safely, and
back returns to the previous Run.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest"`

Expected: FAIL because `AgentRunNavigation` does not exist.

**Step 3: Implement the immutable state**

Add an internal `AgentRunNavigation` value with `selectedRunId`, `canNavigateBack`, `openRoot`, `openChild`, `back`, and
`close`. Keep repository and execution state out of it.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Bind detail phases to Run identity

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVM.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVMTest.kt`

**Step 1: Update state assertions**

Require `Loading(runId)`, `Missing(runId)`, and `Content(detail)` to expose the exact selected Run identity.

**Step 2: Wire navigation through the ViewModel**

Expose `selectedRun`, `canNavigateBack`, `openRun`, `openChildRun`, `navigateBack`, and `closeRun` from the immutable
navigation state. Emit identity-carrying detail phases from `flatMapLatest`.

**Step 3: Compile Android tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Animate the detail sheet and expose back navigation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add UI assertions**

Verify the loading phase exposes indeterminate progress and the content header exposes a back action only for nested
navigation.

**Step 2: Implement keyed native transitions**

Use `AnimatedContent(targetState = state, contentKey = phase + runId)` with restrained fade/vertical motion. Extract the
content pane so telemetry changes with the same Run ID update in place instead of replaying the page transition.

**Step 3: Wire child and back callbacks**

Root card/top-bar entry uses `openRun`; child links use `openChildRun`; the sheet back button calls `navigateBack`.

**Step 4: Compile the app and Android tests**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 4: Final verification

**Files:**
- Verify only.

**Step 1: Run focused regression tests**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Inspect the diff**

Confirm root navigation replaces history, child navigation cannot duplicate/cycle the path, every non-closed detail
state carries its Run ID, telemetry updates retain the same animation key, and `_apk_dl2/` remains untouched.
