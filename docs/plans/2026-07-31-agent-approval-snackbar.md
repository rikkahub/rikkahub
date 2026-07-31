# Agent Approval Snackbar Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the permanent approval-location overlay with transient, accessible Material feedback.

**Architecture:** Map location success to localized Snackbar configuration, own one host state in ChatPage, and report
every lookup result through Scaffold. Remove the custom focus and overlay lifecycle.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, coroutines, JUnit 4.

---

### Task 1: Define stable approval-location feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunNavigationTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Write the failing mapping test**

Require located and missing results to map to distinct localized resource IDs and Snackbar durations.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest.approvalLocationFeedbackDistinguishesResults"`

Expected: FAIL because the feedback model does not exist.

**Step 3: Implement the result mapping and strings**

Use a short duration for success and a long duration with dismiss affordance for missing results.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Replace the custom overlay with SnackbarHost

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

**Step 1: Add the Material host**

Remember one `SnackbarHostState` in `ChatPageContent` and supply `SnackbarHost` through `Scaffold.snackbarHost`.

**Step 2: Route every lookup outcome**

Remove the non-empty-list guard. Scroll on success, then call `showSnackbar` for both success and missing outcomes.

**Step 3: Remove obsolete overlay lifecycle**

Delete announcement state, focus requester/effect, focus modifiers, manual semantics, and overlay text.

**Step 4: Compile the app and Android tests**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run Agent Run regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source and workspace scope**

Confirm `approvalAnnouncement`, `FocusRequester`, and the custom overlay no longer exist; run `git diff --check`, scan
new files for lines over 120 characters, and leave `_apk_dl2/` untouched.
