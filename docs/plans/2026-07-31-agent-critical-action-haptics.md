# Agent Critical Action Haptics Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give native tactile feedback for critical Agent actions without vibrating routine UI.

**Architecture:** Read `LocalHapticFeedback` at the action-owning composable and perform semantic feedback immediately
before the existing callback. Keep asynchronous submission, duplicate prevention, and navigation logic unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Compose UI Test, JUnit 4.

---

### Task 1: Specify approval and stop haptics

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add recording haptic fakes**

Implement test-local `HapticFeedback` recorders and provide them with `CompositionLocalProvider`.

**Step 2: Add approval assertions**

Click approve and deny controls and require `Confirm` and `ContextClick` respectively, alongside the original callbacks.

**Step 3: Add stop assertions**

Click the active Run stop control and require exactly one `Confirm` plus exactly one stop callback.

**Step 4: Compile Android tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS compilation. On a connected device the new assertions fail before haptics are implemented.

### Task 2: Implement semantic action feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add approval feedback**

Perform `Confirm` for approve, `ContextClick` for opening deny, and `Confirm` when the denial is finally submitted.

**Step 2: Add stop feedback**

Perform `Confirm` in active-card and detail-header stop handlers before invoking the existing callback.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions and compile Android tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.components.message.ToolApprovalSubmissionStateTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, inspect touched files against the 120-character limit, and leave `_apk_dl2/` untouched.
