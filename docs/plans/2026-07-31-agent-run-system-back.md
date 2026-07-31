# Agent Run Nested System Back Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Android system back return from a child Run before dismissing the Run detail sheet.

**Architecture:** Select root-versus-child back behavior from immutable detail navigation state. Register a child-only
Compose `BackHandler` inside the modal content, leaving root dismissal to Material 3's native sheet callback.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Activity Compose, Material 3, JUnit 4, Espresso.

---

### Task 1: Specify system-back routing

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunNavigationTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add a failing JVM policy test**

Require closed and root states to delegate dismissal, while a child state navigates to its parent.

**Step 2: Add a Compose system-back test**

Render a child detail sheet, send Espresso back, and require parent navigation without dismissal. Send back again from
the root and require the sheet's dismiss callback.

**Step 3: Run the JVM test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest"`

Expected: FAIL because the back behavior model does not exist.

### Task 2: Add nested system-back handling

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Implement the pure policy selector**

Return `NAVIGATE_PARENT` only when the detail state can navigate back; otherwise return `DISMISS_SHEET`.

**Step 2: Register the nested callback inside the sheet**

Enable `BackHandler` for `NAVIGATE_PARENT` after the detail pane. Invoke `onNavigateBack`; do not intercept root back or
call `onDismiss` directly.

**Step 3: Re-run the focused JVM test**

Expected: PASS.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions and compile Android tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit the patch**

Run `git diff --check`, inspect touched files against the 120-character limit, and leave `_apk_dl2/` untouched.
