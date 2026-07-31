# Agent Run Detail Control Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep live Agent state and a duplicate-safe stop action available while inspecting an active root Run.

**Architecture:** Gate the detail stop callback with a pure identity function, then render a state-aware header from the
existing redacted presentation. Propagate optional stop state through the detail sheet without changing service APIs.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Fence the detail stop target

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunNavigationTest.kt`

**Step 1: Write the failing identity test**

Assert that matching selected/active root IDs return the frozen root ID, while child, stale, and null identities return
`null`.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest.detailStopTargetsOnlyTheActiveRootIdentity"`

Expected: FAIL because `detailStopTarget` does not exist.

**Step 3: Implement the pure gate**

Return `selectedRunId` only when it is non-null and exactly equals `activeRootRunId`.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Build the live detail header

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Add failing Compose assertions**

Require running progress, a clickable stop action, duplicate-safe stopping feedback, live activity text, and terminal
removal of progress/control.

**Step 2: Compile Android test sources and observe failure**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: FAIL until the live header contract exists.

**Step 3: Implement the state-aware header**

Use the existing `AgentRunVisualState`, semantic tint/icon vocabulary, `AnimatedContent`, `AnimatedVisibility`,
`animateColorAsState`, and Material spatial motion. Keep the captured stop callback disabled while stopping.

**Step 4: Compile Android test sources**

Expected: PASS.

### Task 3: Wire root-only control through the detail sheet

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

**Step 1: Propagate optional stop state**

Add defaulted `onStop` and `isStopping` parameters through `AgentRunDetailSheet`, `AgentRunDetailPane`, and content.

**Step 2: Bind the frozen root identity**

Use `detailStopTarget(selectedRunId, activeRun?.id)` in `ChatPage`. Pass no callback for nested or terminal detail and
use the actual ViewModel stopping ID for progress feedback.

**Step 3: Compile the app**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 4: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit identity and source quality**

Run `git diff --check`, scan changed files for lines over 120 characters, confirm child IDs never reach the stop
callback, and leave the user-owned `_apk_dl2/` directory untouched.
