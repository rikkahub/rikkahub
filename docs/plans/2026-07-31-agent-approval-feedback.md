# Agent Approval Feedback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make tool approval submission visibly immediate and duplicate-safe until the actual approval operation finishes.

**Architecture:** Return the existing application-scope approval `Job` through `ChatService`, `ChatVM`, and the message callback without changing approval persistence semantics. Each pending tool card owns a small keyed submission state; it accepts one action, observes the returned Job, and resets on completion or when the durable approval identity changes. The action area uses Material 3 controls and Compose transitions only.

**Tech Stack:** Kotlin coroutines, Jetpack Compose Runtime/Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define and test duplicate-safe submission state

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/components/message/ToolApprovalSubmissionStateTest.kt`

**Step 1: Write the failing state test**

Verify that the first `tryStart()` succeeds, a second call is rejected, `finish()` clears the state, and a later request can start.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.ToolApprovalSubmissionStateTest"`

Expected: FAIL because the state object does not exist.

**Step 3: Implement the minimal state object**

Use Compose snapshot state so UI updates synchronously in the click handler. Keep it UI-only and keyed by approval/execution identity in the composable.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Return the real approval Job through the callback chain

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Change the service return contract**

Return the existing `appScope.launch` Job. Do not move work to the caller scope and do not alter locking, run targeting, or persistence.

**Step 2: Propagate a shared handler type**

Add `ToolApprovalHandler = (...) -> Job`; make `ChatVM.handleToolApproval()` return the service Job and update message/list callback types.

**Step 3: Compile the app**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 3: Add native submitting feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`

**Step 1: Add UI assertions**

Verify idle actions expose approve/deny controls and submitting state replaces both with a progress indicator carrying a localized accessibility label.

**Step 2: Implement the animated action area**

Extract a testable `ToolApprovalActions` composable using `AnimatedContent`. On approve or deny confirmation, synchronously acquire the submission state, invoke the callback once, then join the returned Job in the composition scope and clear state in `finally`.

**Step 3: Compile Android test sources**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 4: Verify approval invariants

**Files:**
- Verify only.

**Step 1: Run focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.ToolApprovalSubmissionStateTest" --tests "me.rerere.rikkahub.service.ChatServiceTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Inspect the final diff**

Confirm approval targeting remains bound to persisted execution/approval IDs, `appScope` still owns the operation, invalid/expired requests complete and release UI state, and `_apk_dl2/` remains untouched.
