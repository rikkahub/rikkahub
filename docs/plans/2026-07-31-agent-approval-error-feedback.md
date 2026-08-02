# Agent Approval Error Feedback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make newly surfaced approval-renewal errors immediately perceivable without replaying haptics for history.

**Architecture:** Track the last visible approval status per stable tool card, emit a one-shot semantic `Reject` only
for changed non-null messages, and keep the service and persisted approval state unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Compose haptics, Material 3, JUnit 4.

---

### Task 1: Specify error-transition semantics

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/components/message/ToolApprovalSubmissionStateTest.kt`

**Step 1: Add the failing state test**

Initialize with a historical message and require no signal for the same value. Then require one signal for a changed
message, none for a duplicate or clear, and one signal when a later error appears.

**Step 2: Run the focused JVM test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.components.message.ToolApprovalSubmissionStateTest"`

Expected: FAIL because `ApprovalStatusFeedbackState` does not exist.

### Task 2: Add one-shot error feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Implement the transition state**

Store the previous message and return true only for a different non-null update.

**Step 2: Connect it to the tool card**

Remember by stable execution/call identity, observe status changes with `LaunchedEffect`, and perform `Reject` only when
the state reports a new error.

**Step 3: Strengthen visual hierarchy**

Render `approvalStatusMessage` with `MaterialTheme.colorScheme.error` while retaining existing size animation.

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
